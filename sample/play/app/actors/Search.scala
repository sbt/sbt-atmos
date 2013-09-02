package actors

import akka.actor.{Actor, ActorRef}

sealed trait SearchActorMessage
object SearchActorMessage {
  case class SearchFor(term:String) extends SearchActorMessage
}

object SearchActor {
  lazy val documents = List("My God, it's full of stars.",
                            "All work and no play makes Jack a dull boy.",
                            "It's a hardball world, son. We've gotta keep our heads until this peace craze blows over.",
                            "I think you're some kind of deviated prevert. I think if general Ripper found out about your preversion, and that you were organizing some kind of mutiny of preverts. Now MOVE!!").map(_.toLowerCase)
}

class SearchActor extends Actor {
  import SearchActor._

  def receive = {
    case SearchActorMessage.SearchFor(term) =>
      val nt = term.trim.toLowerCase
      documents.filter(_.contains(nt)) match {
        case Nil =>
          sender ! None
        case x @ _ =>
          sender ! Some(x)
      }
  }
}