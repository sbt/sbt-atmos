package com.typesafe.atmos.sample

import akka.actor._
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit.MILLISECONDS
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom

object TestSample {
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
