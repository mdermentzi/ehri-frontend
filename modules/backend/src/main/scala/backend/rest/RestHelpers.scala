package backend.rest

import play.api.cache.CacheApi
import play.api.libs.json.{JsString, JsValue}
import defines.EntityType
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import backend.rest.cypher.Cypher


trait RestHelpers {

  implicit def app: play.api.Application
  implicit def cache: CacheApi
  def cypher: Cypher

  def parseUsers(json: JsValue): Seq[(String, String)] = {
    (json \ "data").as[Seq[Seq[String]]].flatMap {
      case x :: y :: _ => Some((x, y))
      case _ => None
    }
  }

  def getGroupList: Future[Seq[(String,String)]] = {
    cypher.cypher("START n=node:entities(__ISA__ = {isA}) RETURN n.__ID__, n.name",
        Map("isA" -> JsString(EntityType.Group))).map { goe =>
      parseUsers(goe)
    }    
  }
  
  def getUserList: Future[Seq[(String,String)]] = {
    cypher.cypher("START n=node:entities(__ISA__ = {isA}) RETURN n.__ID__, n.name",
        Map("isA" -> JsString(EntityType.UserProfile))).map { goe =>
      parseUsers(goe)
    }    
  }  
}