package controllers.admin

import javax.inject._

import controllers.Components
import controllers.base.AdminController
import controllers.generic.Search
import defines.{EntityType, EventType}
import models.base.{AnyModel, Description}
import models.{Isaar, SystemEvent}
import play.api.http.MimeTypes
import play.api.i18n.Messages
import play.api.libs.json.Json
import play.api.mvc._
import utils.search._
import utils.{PageParams, SystemEventParams}
import views.Helpers

import scala.concurrent.Future.{successful => immediate}


@Singleton
case class Home @Inject()(components: Components) extends AdminController with Search {

  val searchEntities = List(
    EntityType.DocumentaryUnit,
    EntityType.Repository,
    EntityType.HistoricalAgent
  )

  private val entityFacets: FacetBuilder = { implicit request =>
    List(

      FieldFacetClass(
        key=Description.LANG_CODE,
        name=Messages("documentaryUnit." + Description.LANG_CODE),
        param="lang",
        render= (s: String) => Helpers.languageCodeToName(s)
      ),

      // Holding repository
      FieldFacetClass(
        key="repositoryName",
        name=Messages("documentaryUnit.heldBy"),
        param="holder"
      ),

      // Repositories by country
      FieldFacetClass(
        key="countryCode",
        name=Messages("repository.countryCode"),
        param="country",
        render= (s: String) => Helpers.countryCodeToName(s)
      ),

      // Historical agent type
      FieldFacetClass(
        key=models.Isaar.ENTITY_TYPE,
        name=Messages("historicalAgent." + Isaar.ENTITY_TYPE),
        param="cpf",
        render=s => Messages("historicalAgent." + s)
      )
    )
  }

  def index(params: SystemEventParams, range: utils.RangeParams): Action[AnyContent] = OptionalUserAction.async { implicit request =>
    val activityEventTypes = List(
      EventType.deletion,
      EventType.creation,
      EventType.modification,
      EventType.modifyDependent,
      EventType.createDependent,
      EventType.deleteDependent,
      EventType.link,
      EventType.annotation
    )

    val activityItemTypes = List(
      EntityType.DocumentaryUnit,
      EntityType.Repository,
      EntityType.Country,
      EntityType.HistoricalAgent
    )

    request.userOpt.map { user =>
      val eventFilter = params.copy(eventTypes = activityEventTypes)
        .copy(itemTypes = activityItemTypes)
      userDataApi.userEvents[SystemEvent](user.id, range, eventFilter).map { events =>
        Ok(views.html.admin.index(Some(events)))
      }
    } getOrElse {
      immediate(Ok(views.html.admin.index(None)))
    }
  }

  def metrics = OptionalUserAction { implicit request =>
    Ok(views.html.admin.metrics())
  }

  def loginRedirect() = Action {
    MovedPermanently(controllers.portal.account.routes.Accounts.loginOrSignup().url)
  }

  // NB: This page now just handles metrics and only provides facet
  // data via JSON.
  def overview: Action[AnyContent] = OptionalUserAction.async { implicit request =>
    find[AnyModel](SearchParams.empty, PageParams(limit = 0), facetBuilder = entityFacets).map { result =>
        render {
          case Accepts.Json() => Ok(Json.toJson(Json.obj("facets" -> result.facetClasses)))
          case _ => MovedPermanently(controllers.admin.routes.Home.metrics().url)
        }
    }
  }

  def metricsJsRoutes = Action { implicit request =>
    Ok(
      play.api.routing.JavaScriptReverseRouter("metricsJsRoutes")(
        controllers.admin.routes.javascript.Metrics.languageOfMaterial,
        controllers.admin.routes.javascript.Metrics.holdingRepository,
        controllers.admin.routes.javascript.Metrics.repositoryCountries,
        controllers.admin.routes.javascript.Metrics.agentTypes,
        controllers.admin.routes.javascript.Metrics.restricted
      )
    ).as(MimeTypes.JAVASCRIPT)
  }
}
