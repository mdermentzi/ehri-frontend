package controllers.core

import defines._
import models.SystemEvent
import play.api.libs.concurrent.Execution.Implicits._
import com.google.inject._
import global.GlobalConfig
import utils.{ListParams, SystemEventParams, PageParams}
import controllers.generic.Read

case class SystemEvents @Inject()(implicit globalConfig: GlobalConfig, backend: rest.Backend) extends Read[SystemEvent] {

  implicit val resource = SystemEvent.Resource

  val entityType = EntityType.SystemEvent
  val contentType = ContentTypes.SystemEvent

  def get(id: String) = getAction.async(id) {
      item => annotations => links => implicit userOpt => implicit request =>
    // In addition to the item itself, we also want to fetch the subjects associated with it.
    val params = PageParams.fromRequest(request)
    val subjectParams = PageParams.fromRequest(request, namespace = "s")
        backend.subjectsForEvent(id, params).map { page =>
      Ok(views.html.systemEvents.show(item, page, params))
    }
  }

  def list = userProfileAction.async { implicit userOpt => implicit request =>
    val listParams = ListParams.fromRequest(request)
    val eventFilter = SystemEventParams.fromRequest(request)
    val filterForm = SystemEventParams.form.fill(eventFilter)
    backend.listEvents(listParams, eventFilter).map { list =>
      Ok(views.html.systemEvents.list(list, listParams, filterForm))
    }
  }
}
