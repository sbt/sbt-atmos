package controllers

import play.api._
import play.api.mvc._

object Application extends Controller {

  def index = Action {
    Ok(views.html.main())
  }

  // GET
  def helloGet = Action {
    Ok("Hello world: GET")
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