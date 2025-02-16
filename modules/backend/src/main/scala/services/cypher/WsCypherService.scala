package services.cypher

import akka.actor.ActorSystem
import akka.stream.alpakka.json.scaladsl.JsonReader
import akka.stream.scaladsl.Source
import akka.stream.{Materializer, scaladsl}
import akka.util.ByteString
import config.ServiceConfig
import play.api.Logger
import play.api.cache.SyncCacheApi
import play.api.http.HttpVerbs
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsValue, Json, Reads, __}
import play.api.libs.ws.{WSClient, WSRequest}
import services.data.Constants.STREAM_HEADER_NAME

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}


private case class WsCypherResultData(row: List[JsValue], meta: Seq[JsValue])

private object WsCypherResultData {
  implicit val reads: Reads[WsCypherResultData] = (
    (__ \ "row").read[List[JsValue]] and
    (__ \ "meta").read[Seq[JsValue]]
  )(WsCypherResultData.apply _)
}

private case class WsCypherResult(columns: Seq[String], data: Seq[WsCypherResultData]) {
  def asLegacy: CypherResult = CypherResult(columns, data.map(_.row))
}

private object WsCypherResult {
  implicit val reads: Reads[WsCypherResult] = (
    (__ \ "columns").read[Seq[String]] and
    (__ \ "data").read[Seq[WsCypherResultData]]
  )(WsCypherResult.apply _)
}


@Singleton
case class WsCypherService @Inject ()(
  ws: WSClient,
  cache: SyncCacheApi,
  config: play.api.Configuration)(
    implicit actorSystem: ActorSystem, mat: Materializer, executionContext: ExecutionContext)
  extends CypherService {

  val logger: Logger = play.api.Logger(getClass)

  override def get(scriptBody: String, params: Map[String,JsValue] = Map.empty): Future[CypherResult] =
    raw(scriptBody, params).execute(HttpVerbs.POST)
      .map(r => (r.json \ "results" \ 0).as[WsCypherResult].asLegacy)

  override def rows(scriptBody: String, params: Map[String,JsValue] = Map.empty): Source[List[JsValue], _] = {
    scaladsl.Source.future(raw(scriptBody, params).stream().map { sr =>
      sr.bodyAsSource
        .via(JsonReader.select("$.results[0].data[*].row"))
        .map { rowBytes =>
          Json.parse(rowBytes.toArray).as[List[JsValue]]
        }
    }).flatMapConcat(identity)
  }

  override def legacy(scriptBody: String, params: Map[String,JsValue] = Map.empty): Source[ByteString, _] = {
    val data = Json.obj(
      "query" -> scriptBody,
      "params" -> params
    )
    logger.debug(s"Legacy Cypher: ${Json.toJson(data)}")

    Source.future(
      request("legacyCypher")
        .withBody(data)
        .stream()
        .map(_.bodyAsSource))
    .flatMapConcat(identity)
  }

  private def raw(scriptBody: String, params: Map[String,JsValue] = Map.empty): WSRequest = {
    val data = Json.obj("statements" -> Json.arr(
        Json.obj(
      "statement" -> scriptBody,
            "parameters" -> params
        )
    ))
    logger.debug(s"Cypher: ${Json.toJson(data)}")
    request("cypher").withBody(data)
  }

  private def request(name: String) = {
    val serviceConfig = ServiceConfig(name, config)
    ws.url(serviceConfig.baseUrl)
      .addHttpHeaders(serviceConfig.authHeaders: _*)
      .addHttpHeaders(STREAM_HEADER_NAME -> "true")
      .withMethod(HttpVerbs.POST)
  }
}
