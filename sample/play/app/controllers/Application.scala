package controllers

import play.api._
import actors._
import utils._
import play.api.mvc._
import akka.pattern.{ ask, pipe }
import play.api.libs.concurrent.Akka
import play.api.Play.current
import akka.util.Timeout
import scala.concurrent.duration._

// import Akka.system.dispatcher

object Application extends Controller {

  def index = Action {
    Ok(views.html.main())
  }

  // GET
  def helloGet = Action {
    Ok("Hello world: GET")
  }

  def helloSearch(term:String) = Action.async {
    implicit val d = Akka.system.dispatcher
    for {
      result <- ask(Global.searchActor,SearchActorMessage.SearchFor(term))(Timeout(5.seconds)).mapTo[Option[Seq[String]]]
    } yield {
      result.map { r =>
        Ok("Results: \r\n"+r.map(_ + "\r\n"))
      } getOrElse Ok("No results found")
    }
  }

  // POST
  def helloPost = Action {
    Ok("Hello world: POST")
  }

  // DELETE
  def helloDelete = Action {
    Ok("Hello world: DELETE")
  }

  // PUT
  def helloPut = Action {
    Ok("Hello world: PUT")
  }

  // POST && File upload
  def helloFile = Action(parse.multipartFormData) { request =>
    request.body.file("picture").map { picture =>
      import java.io.File
      val filename = picture.filename
      val contentType = picture.contentType
      picture.ref.moveTo(new File("/tmp/picture"), true)
      Ok("File uploaded")
    }.getOrElse {
      BadRequest("Not Ok")
    }
  }


  def notFound = Action {
    NotFound("Not Ok")
  }

  def badRequest = Action {
    BadRequest("Not Ok")
  }

}