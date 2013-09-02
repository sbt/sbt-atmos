package utils

import actors.SearchActor
import akka.actor.{ActorRef, Props}
import play.{Application,GlobalSettings,Play}
import play.libs.Akka

object Global {
  var searchActor:ActorRef = _
}

class Global extends GlobalSettings {
  override def onStart(application:Application):Unit = {
    Global.searchActor = Akka.system.actorOf(Props[SearchActor], "search")
  }
}
