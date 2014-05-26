//
// Scaled JUnit Runner - integrates JUnit with Scaled's project services
// http://github.com/scaled/junit-runner/blob/master/LICENSE

package scaled.junit

import scaled.prococol.{Receiver, Sender}

object Main {

  val sender = new Sender(System.out, true)
  val server = new Server(sender)

  def main (args :Array[String]) {
    // read prococol messages from stdin and pass them to server
    val recv = new Receiver(System.in, server)
    recv.run()
  }
}
