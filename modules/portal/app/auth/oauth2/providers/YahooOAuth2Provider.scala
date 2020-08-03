package auth.oauth2.providers

import auth.oauth2.{OAuth2Info, UserData}
import com.fasterxml.jackson.core.JsonParseException
import org.apache.commons.codec.binary.Base64
import play.api.http.ContentTypes
import play.api.libs.json.{JsValue, Json, Reads}

case class YahooOAuth2Provider (config: play.api.Configuration) extends OAuth2Provider {

  val name = "yahoo"

  private case class YahooEmail(handle: String, id: Int, primary: Option[Boolean])
  private object YahooEmail {
    implicit val reader: Reads[YahooEmail] = Json.reads[YahooEmail]
  }

  override def getUserInfoHeader(info: OAuth2Info): Seq[(String, String)] =
    Seq("Authorization" -> s"Bearer ${info.accessToken}")

  override def getAccessTokenHeaders: Seq[(String, String)] = {
    // Base64 encode the clientId:clientSecret
    val encoded: String = new Base64()
      .encodeToString(s"${settings.clientId}:${settings.clientSecret}".getBytes)
    Seq(
      "Authorization" -> s"Basic $encoded",
      "Content-Type" -> ContentTypes.FORM
    )
  }

  override def getAccessTokenParams(code: String, handlerUrl: String): Map[String,Seq[String]] =
    Map(
      "grant_type" -> Seq("authorization_code"),
      "redirect_uri" -> Seq(handlerUrl),
      "code" -> Seq(code)
    )

  override def parseUserInfo(data: String): Option[UserData] = {
    try {
      val json: JsValue = Json.parse(data)
      logger.debug(s"Google user info $json")
      for {
        guid <- (json \ "sub").asOpt[String]
        email <- (json \ "email").asOpt[String]
        name <- (json \ "name").asOpt[String]
        imageUrl <- (json \ "picture").asOpt[String]
      } yield UserData(
        providerId = guid,
        email = email,
        name = name,
        imageUrl = imageUrl
      )
    } catch {
      case _: JsonParseException => None
    }
  }

  override def parseAccessInfo(data: String): Option[OAuth2Info] = {
    val info: Option[OAuth2Info] = super.parseAccessInfo(data)
    try {
      info.map(_.copy(userGuid = (Json.parse(data) \ "xoauth_yahoo_guid").asOpt[String]))
    } catch {
      case _: JsonParseException => None
    }
  }
}
