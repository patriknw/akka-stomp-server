# Akka STOMP Server

Prototype of STOMP Server with seamless integration with 
[Akka](http://akka.io/) actors.
So far it only implements essential parts of STOMP 1.0 protocol.
http://stomp.github.com/stomp-specification-1.0.html

I will probably not have time to take this further. You are welcome
to use it as a starting point for an 
[Akka contribution](http://doc.akka.io/docs/akka/snapshot/contrib/index.html)
or use it as example for learning [Akka IO](http://doc.akka.io/docs/akka/snapshot/scala/io.html)

Messages can be sent from client to an actor on the server with path defined
in `destination` header of the SEND frame. `actorFor` is used to lookup the
actor from the `destination` string.

Client start subscription by specifying the path of the topic actor in 
the `destination` header of the SUBSCRIBE frame.
`actorFor` is used to lookup the actor from the `destination` string.
Actors that are used as destination for subscriptions need
to handle `RegisterSubscriber` message. A generic `Topic` actor is
provided. It will send all received messages to registered
subscribers. You may use any other actor as a subscription
destination as long as it provides similar functionality, i.e.
handles `RegisterSubscriber` message and watch the subscriber for
termination.

## Try it

Most fun is to try the chat example that is included. It uses 
the [STOMP Over WebSocket](http://www.jmesnil.net/stomp-websocket/doc/)
JavaScript library in the browser client to interact with Akka actors on
the server.

Clone this repository:

    git clone http://github.com/patriknw/akka-stomp-server.git
    cd akka-stomp-server

Start the STOMP server:

    sbt run

WebSocket handshake is not implemented so easiest is to use
[EventMachine Websocket Proxy](https://github.com/mcolyer/em-websocket-proxy):

    * gem install em-websocket-proxy
    * em-websocket-proxy -p 8081 -r localhost -q 8080

Open [example/chat/index.html](http://htmlpreview.github.com/?https://github.com/patriknw/akka-stomp-server/blob/master/example/chat/index.html)
and click Connect.

Open same file again [example/chat/index.html](http://htmlpreview.github.com/?https://github.com/patriknw/akka-stomp-server/blob/master/example/chat/index.html)
in another browser window and click Connect. Enter message at the bottom of the
page and see how it is displayed in the first browser window.

When you click Connect the page starts a subscription to the destination `/user/mytopic`, 
which corresponds to the path of an actor on the server. When you enter a message
it is sent to that actor, which publishes it to all subscribers, i.e. you should see it 
in both browser windows.

