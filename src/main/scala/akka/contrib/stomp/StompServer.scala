package akka.contrib.stomp

import language.postfixOps

import akka.actor.{ Actor, ActorRef, ActorSystem, IO, IOManager, PoisonPill, Props, Terminated }
import akka.util.{ ByteString, ByteStringBuilder }
import java.net.InetSocketAddress

/**
 * Prototype of STOMP Server with seamless integration with Akka actors.
 * So far it only implements essential parts of STOMP 1.0 protocol.
 * http://stomp.github.com/stomp-specification-1.0.html
 *
 * Messages can be sent from client to actor with path defined
 * in `destination` header of the SEND frame. `actorFor` is used to lookup the
 * actor from the `destination` string.
 *
 * Client start subscription (STOMP SUBSCRIBE) by specifying the path of
 * the topic actor in the `destination` header of the SUBSCRIBE frame.
 * `actorFor` is used to lookup the actor from the `destination` string.
 * Actors that are used as destination for subscriptions need
 * to handle `RegisterSubscriber` message. A generic `Topic` actor is
 * provided. It will send all received messages to registered
 * subscribers. You may use any other actor as a subscription
 * destination as long as it provides similar functionality, i.e.
 * handles `RegisterSubscriber` message and watch the subscriber for
 * termination.
 */
class StompServer(port: Int) extends Actor {
  import StompMessageProtocol._

  val state = IO.IterateeRef.Map.async[IO.Handle]()(context.dispatcher)
  val requestHandler = context.actorOf(Props[RequestHandler], "requestHandler")

  override def preStart {
    IOManager(context.system) listen new InetSocketAddress(port)
  }

  def receive = {

    case IO.NewClient(server) ⇒
      val socket = server.accept()
      state(socket) flatMap (_ ⇒ StompWireProtocol.processRequest(socket, requestHandler))

    case IO.Read(socket, bytes) ⇒
      state(socket)(IO Chunk bytes)

    case IO.Closed(socket, cause) ⇒
      requestHandler ! ClientClosed(socket)
      state(socket)(IO EOF)
      state -= socket

  }

}

/**
 * Actors that are used as destination for subscriptions need
 * to handle `RegisterSubscriber` message. It is sent when a client
 * starts a subscription (STOMP SUBSCRIBE). The `sender` of the
 * `RegisterSubscriber` message is the subscriber `ActorRef`
 * that expect published messages from the topic.
 */
case object RegisterSubscriber

/**
 * Generic topic, i.e. can be used as destination for
 * STOMP SUBSCRIBE. It will send all received messages to registered
 * subscribers. You may use any other actor as a subscription
 * destination as long as it provides similar functionality, i.e.
 * handles `RegisterSubscriber` message and watch the subscriber for
 * termination.
 */
class Topic extends Actor {
  var subscribers = Set.empty[ActorRef]

  def receive = {
    case RegisterSubscriber ⇒
      context.watch(sender)
      subscribers += sender
    case Terminated(subscriber) ⇒
      subscribers -= subscriber
    case msg ⇒
      subscribers foreach { _ ! msg }
  }
}

private[stomp] class RequestHandler extends Actor {
  import StompMessageProtocol._
  import StompWireProtocol._

  var subscribers = Map.empty[IO.Handle, Map[String, ActorRef]].
    withDefaultValue(Map.empty.withDefaultValue(context.system.deadLetters))

  def receive = {
    case ClientRequest(socket, frame) ⇒ frame match {
      case Connect(acceptVersion) ⇒
        if (acceptVersion.split(",").contains("1.0"))
          socket.asWritable write ConnectedOkResponse.bytes.compact
        else {
          socket.asWritable write ErrorResponse("Supported protocol versions are 1.0").bytes.compact
          socket.close()
        }

      case Subscribe(destination, subscriptionId) ⇒
        val topicRef = context.actorFor(destination)
        val subscriber = context.actorOf(Props(new Subscriber(
          topicRef, destination, subscriptionId, socket.asWritable)))
        subscribers += (socket -> (subscribers(socket) + (subscriptionId -> subscriber)))

      case Unsubscribe(subscriptionId) ⇒
        subscribers(socket)(subscriptionId) ! PoisonPill

      case Send(destination, body) ⇒
        context.actorFor(destination) ! body

      case Disconnect ⇒ // ok

      case Unknown(command, headers) ⇒
        val headersStr = headers.map { case (k, v) ⇒ k + ":" + v } mkString "\n"
        socket.asWritable write
          ErrorResponse(s"Unknown command [${command}] or missing headers, got headers\n${headersStr}").
          bytes.compact
    }

    case ClientClosed(socket) ⇒
      subscribers(socket).values foreach { _ ! PoisonPill }
      subscribers -= socket

  }
}

/**
 * INTERNAL API
 */
private[stomp] object StompWireProtocol {
  import StompMessageProtocol._
  val FrameEnd = ByteString(0x00) // null byte
  val LineFeed = ByteString(0x0A)
  val Colon = ByteString(":")
  val ContentType = ByteString("content-type:")
  val ContentTypeTextPlain = ByteString("content-type:text/plain")
  val ContentLength = ByteString("content-length:")

  // name/value tuple
  type Header = (String, String)

  def processRequest(socket: IO.Handle, requestHandler: ActorRef): IO.Iteratee[Unit] =
    IO repeat {
      for (frame ← readRequest) yield {
        requestHandler ! ClientRequest(socket, frame)
      }
    }

  def readRequest: IO.Iteratee[ClientFrame] =
    for {
      _ ← dropLineFeeds
      command ← readCommandLine
      headers ← readHeaders
      bodyOption ← readBody
    } yield {
      val headersMap = headers.toMap
      (command, bodyOption) match {
        case ("CONNECT", _) if headersMap.contains("accept-version") ⇒
          Connect(headersMap("accept-version"))
        case ("SUBSCRIBE", _) if headersMap.contains("destination") && headersMap.contains("id") ⇒
          Subscribe(headersMap("destination"), headersMap("id"))
        case ("UNSUBSCRIBE", _) if headersMap.contains("id") ⇒
          Unsubscribe(headersMap("id"))
        case ("SEND", Some(body)) if headersMap.contains("destination") ⇒
          Send(headersMap("destination"), body)
        case ("DISCONNECT", _) ⇒
          Disconnect
        case _ ⇒
          Unknown(command, headersMap)
      }
    }

  def dropLineFeeds: IO.Iteratee[Unit] =
    IO peek LineFeed.length flatMap {
      case LineFeed ⇒
        IO takeUntil LineFeed flatMap (_ ⇒ dropLineFeeds)
      case _ ⇒ IO Done ()
    }

  def readCommandLine = {
    for {
      command ← IO takeUntil LineFeed
    } yield command.decodeString("utf-8")
  }

  def readHeaders = {
    def step(found: List[Header]): IO.Iteratee[List[Header]] = {
      IO peek LineFeed.length flatMap {
        case LineFeed ⇒ IO takeUntil LineFeed flatMap (_ ⇒ IO Done found)
        case _        ⇒ readHeader flatMap (header ⇒ step(header :: found))
      }
    }
    step(Nil)
  }

  def readHeader =
    for {
      name ← IO takeUntil Colon
      value ← IO takeUntil LineFeed // TODO decode
    } yield (name.decodeString("utf-8"), value.decodeString("utf-8"))

  def readBody =
    for {
      body ← IO takeUntil FrameEnd
    } yield {
      if (body.isEmpty) None
      else Some(body.decodeString("utf-8")) // TODO handle non-string body?
    }

  object ConnectedOkResponse {
    val bytes: ByteString = {
      new ByteStringBuilder ++=
        ByteString("CONNECTED") ++= LineFeed ++=
        ByteString("version:1.0") ++= LineFeed ++=
        LineFeed ++=
        FrameEnd result
    }
  }

  case class ErrorResponse(msg: String) {
    def bytes: ByteString = {
      val msgBytes = ByteString(msg)
      new ByteStringBuilder ++=
        ByteString("ERROR") ++= LineFeed ++=
        ByteString("version:1.0") ++= LineFeed ++=
        ContentTypeTextPlain ++= LineFeed ++=
        ContentLength ++= ByteString(msgBytes.length.toString) ++= LineFeed ++=
        LineFeed ++=
        msgBytes ++=
        FrameEnd result
    }
  }

  object Message {
    val MessageCommand = ByteString("MESSAGE")
    val Subscription = ByteString("subscription:")
    val MessageId = ByteString("message-id:")
    val Destination = ByteString("destination:")
  }

  case class Message(body: ByteString, destination: String, subscriptionId: String, messageId: String) {
    def bytes: ByteString = {
      import Message._
      new ByteStringBuilder ++=
        MessageCommand ++= LineFeed ++=
        Subscription ++= ByteString(subscriptionId) ++= LineFeed ++=
        MessageId ++= ByteString(messageId) ++= LineFeed ++=
        Destination ++= ByteString(destination) ++= LineFeed ++=
        ContentType ++= ByteString("text/plain") ++= LineFeed ++=
        ContentLength ++= ByteString(body.length.toString) ++= LineFeed ++=
        LineFeed ++=
        body ++= FrameEnd result
    }
  }

}

/**
 * INTERNAL API
 */
private[stomp] object StompMessageProtocol {

  case class Connect(acceptVersion: String) extends ClientFrame

  case object Disconnect extends ClientFrame

  case class Subscribe(destination: String, subscriptionId: String) extends ClientFrame

  case class Unsubscribe(subscriptionId: String) extends ClientFrame

  case class Send(destination: String, body: String) extends ClientFrame

  case class Unknown(command: String, headers: Map[String, String]) extends ClientFrame

  trait ClientFrame

  case class ClientRequest(socket: IO.Handle, frame: ClientFrame)

  case class ClientClosed(socket: IO.Handle)

}

/**
 * INTERNAL API
 */
private[stomp] class Subscriber(topic: ActorRef, destination: String, subscriptionId: String, socket: IO.WriteHandle)
  extends Actor {

  var counter = 0L

  topic ! RegisterSubscriber
  context.watch(topic)

  def receive = {
    case Terminated(`topic`) ⇒ self ! PoisonPill
    case body: ByteString    ⇒ write(body)
    case body                ⇒ write(ByteString(body.toString))
  }

  def write(body: ByteString): Unit = {
    counter += 1
    val messageId = counter.toString
    socket write StompWireProtocol.Message(body, destination, subscriptionId, messageId).
      bytes.compact
  }
}

/**
 * Example application that creates a `Topic` actor named
 * `/user/mytopic` and starts a `StompServer` listening on
 * port 8080 (by default).
 */
object StompSample extends App {
  val port = Option(System.getenv("PORT")) map (_.toInt) getOrElse 8080
  val system = ActorSystem("StompSample")
  system.actorOf(Props[Topic], "mytopic")
  system.actorOf(Props(new StompServer(port)))
}
