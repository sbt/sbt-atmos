package com.typesafe.atmos.sample

import akka.actor._
import akka.event.LoggingReceive
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit.MILLISECONDS
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom

object Sample {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem("Sample")

    val actorB = system.actorOf(Props[ActorB], "ActorB")
    val actorA = system.actorOf(Props(new ActorA(actorB)), "ActorA")

    implicit val executionContext = system.dispatcher

    // a few messages each second
    for (n ← 1 to 5) {
      system.scheduler.schedule(
        Duration(3000 + ThreadLocalRandom.current.nextInt(1000), MILLISECONDS),
        4.seconds, actorA, "msg")
    }

    // peak during 5 seconds each minute
    for (n ← 1 to 50) {
      system.scheduler.schedule(Duration(10000 + (50 * n), MILLISECONDS),
        1.minute, actorA, "msg")
    }

    // an error once in a while
    system.scheduler.schedule(30.seconds, 15.minutes, actorA, "err")
  }
}

case class ReplyData(description: String, payload: Array[Byte]) {
  override def toString = description
}

class ActorA(actorB: ActorRef) extends Actor with ActorLogging {
  val markerName = self.path.name + "-roundtrip"

  def receive = LoggingReceive {

    case message @ "msg" ⇒
      actorB ! "msg-A-tell-B"

    case "err" ⇒ throw new RuntimeException("Simulated exception in " + self.path.name)

    case ReplyData(description, payload) ⇒

  }
}

class ActorB extends Actor {
  val actorC = context.actorOf(Props[ActorC], self.path.name.dropRight(1) + "C")
  val simulatedDelayMillis = 50

  def receive = LoggingReceive {
    case message: String ⇒
      Thread.sleep(simulatedDelayMillis)
      actorC.forward("msg-B-forward-C")
  }
}

class ActorC extends Actor {
  def receive = LoggingReceive {
    case message: String ⇒
      val rnd = ThreadLocalRandom.current
      val payload = Array.ofDim[Byte](rnd.nextInt(1000))
      rnd.nextBytes(payload)
      sender ! ReplyData("msg-C-reply-A", payload)
  }
}

