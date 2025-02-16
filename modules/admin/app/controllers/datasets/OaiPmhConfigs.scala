package controllers.datasets

import actors.harvesting.OaiPmhHarvester.{OaiPmhHarvestData, OaiPmhHarvestJob}
import actors.harvesting.{HarvesterManager, OaiPmhHarvester}
import akka.actor.{ActorContext, Props}
import akka.stream.Materializer
import controllers.AppComponents
import controllers.base.AdminController
import controllers.generic.Update
import models.HarvestEvent.HarvestEventType
import models.{FileStage, OaiPmhConfig, Repository}
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import services.harvesting.{HarvestEventService, OaiPmhClient, OaiPmhConfigService, OaiPmhError}
import services.storage.FileStorage

import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Future
import scala.concurrent.Future.{successful => immediate}

@Singleton
case class OaiPmhConfigs @Inject()(
  controllerComponents: ControllerComponents,
  @Named("dam") storage: FileStorage,
  appComponents: AppComponents,
  oaipmhConfigs: OaiPmhConfigService,
  oaiPmhClient: OaiPmhClient,
  harvestEvents: HarvestEventService,
)(implicit mat: Materializer) extends AdminController with StorageHelpers with Update[Repository] {

  def get(id: String, ds: String): Action[AnyContent] = EditAction(id).async { implicit request =>
    oaipmhConfigs.get(id, ds).map { opt =>
      Ok(Json.toJson(opt))
    }
  }

  def save(id: String, ds: String): Action[OaiPmhConfig] = EditAction(id).async(parse.json[OaiPmhConfig]) { implicit request =>
    oaipmhConfigs.save(id, ds, request.body).map { r =>
      Ok(Json.toJson(r))
    }
  }

  def delete(id: String, ds: String): Action[AnyContent] = EditAction(id).async { implicit request =>
    oaipmhConfigs.delete(id, ds).map(_ => NoContent)
  }

  def test(id: String, ds: String): Action[OaiPmhConfig] = EditAction(id).async(parse.json[OaiPmhConfig]) { implicit request =>
    val getIdentF = oaiPmhClient.identify(request.body)
    val listIdentF = oaiPmhClient.listIdentifiers(request.body)
    (for (ident <- getIdentF; _ <- listIdentF)
      yield Ok(Json.toJson(ident))).recover {
      case e: OaiPmhError => BadRequest(Json.obj("error" -> e.errorMessage))
      case e => InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }

  def harvest(id: String, ds: String, fromLast: Boolean): Action[OaiPmhConfig] = EditAction(id).async(parse.json[OaiPmhConfig]) { implicit request =>
    val lastHarvest: Future[Option[Instant]] =
      if (fromLast) harvestEvents.get(id, Some(ds)).map(events =>
        events
          .filter(_.eventType == HarvestEventType.Completed)
          .map(_.created)
          .lastOption
      ) else immediate(Option.empty[Instant])

    lastHarvest.map { last =>
      val endpoint = request.body
      val jobId = UUID.randomUUID().toString
      val data = OaiPmhHarvestData(endpoint, prefix = prefix(id, ds, FileStage.Input), from = last)
      val job = OaiPmhHarvestJob(id, ds, jobId, data = data)
      val init = (context: ActorContext) => context.actorOf(Props(OaiPmhHarvester(oaiPmhClient, storage)))
      mat.system.actorOf(Props(HarvesterManager(job, init, harvestEvents)), jobId)

      Ok(Json.obj(
        "url" -> controllers.admin.routes.Tasks
          .taskMonitorWS(jobId).webSocketURL(conf.https),
        "jobId" -> jobId
      ))
    }
  }
}
