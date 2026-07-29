package utils

import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._

/**
  * Replays AppReg's production SSO protocol at HTTP level. Gatling maintains the
  * appreg.sid cookie per virtual user; no cookies, passwords, tokens or Entra
  * dynamic values are written to disk or logs.
  */
object SsoAuthentication {

  private def environmentVariable(names: String*): Option[String] =
    names.iterator.map(System.getenv).find(value => value != null && value.nonEmpty)

  private def requiredEnvironmentVariable(names: String*): String =
    environmentVariable(names: _*).getOrElse(
      throw new IllegalArgumentException(s"Set one of: ${names.mkString(", ")}")
    )

  private def accountName(template: String, index: Int, accountCount: Int): String = {
    if (template.contains("{index}")) template.replace("{index}", index.toString)
    else if (accountCount == 1) template
    else throw new IllegalArgumentException("TEST_USER_EMAIL or APPREG_TEST_ACCOUNT_TEMPLATE must contain {index} for an SSO run with multiple users")
  }

  /** One dedicated account per virtual user. The password is never added to a session feeder. */
  def users(accountCount: Int): Iterator[Map[String, String]] = {
    require(accountCount > 0, "The SSO account count must be greater than zero")
    val template = requiredEnvironmentVariable("APPREG_TEST_ACCOUNT_TEMPLATE", "TEST_USER_EMAIL")
    val firstIndex = environmentVariable("APPREG_ACCOUNT_START_INDEX").map(_.toInt).getOrElse(1)
    (firstIndex until firstIndex + accountCount).iterator.map { index =>
      Map("username" -> accountName(template, index, accountCount))
    }
  }

  private def password = requiredEnvironmentVariable("APPREG_TEST_USER_PASSWORD", "TEST_USERS_PASSWORD")
  private def tenantId = requiredEnvironmentVariable("APPREG_TENANT_ID", "TENANT_ID")
  private val microsoftLoginBaseUrl = "https://login.microsoftonline.com"

  private val browserHeaders = Map(
    "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" -> "en-GB,en;q=0.9",
    "Upgrade-Insecure-Requests" -> "1"
  )

  private val credentialDiscoveryBody =
    """{"username":"#{username}","isOtherIdpSupported":true,"checkPhones":false,"isRemoteNGCSupported":true,"isCookieBannerShown":false,"isFidoSupported":true,"country":"GB","forceotclogin":false,"isExternalFederationDisallowed":false,"isRemoteConnectSupported":false,"federationFlags":0,"isSignup":false,"flowToken":"#{entraFlowToken}","isAccessPassSupported":true,"isQrCodePinSupported":true}"""

  /** Captures the callback URL emitted by Entra's post-password page. */
  private val callbackUrlCheck = regex(
    """(?is).*?((?:https?:\\?/\\?/[^\\"'\s<>]+)?/sso/login-callback[^\\"'\s<>]+).*"""
  ).saveAs("entraCallbackUrl")

  def login: ChainBuilder =
    group("AppReg_000_SSO_Login") {
      exec(
        http("AppReg SSO login redirect")
          .get("/sso/login")
          .headers(browserHeaders)
          .disableFollowRedirect
          .check(status.is(302))
          .check(headerRegex("Location", """(.+)""").saveAs("entraAuthorizeUrl"))
          .check(headerRegex("Location", """[?&]state=([^&]+)""").saveAs("oauthState"))
      )
        .exec(
          http("Entra authorize")
            .get("#{entraAuthorizeUrl}")
            .headers(browserHeaders ++ Map("Referer" -> (Environment.baseURL + "/login")))
            .check(status.is(200))
            .check(regex("""(?s).*?\"sessionId\"\s*:\s*\"([^\"]+)\".*""").saveAs("entraSessionId"))
            .check(regex("""(?s).*?\"sCtx\"\s*:\s*\"([^\"]+)\".*""").saveAs("entraContext"))
            .check(regex("""(?s).*?\"sFT\"\s*:\s*\"([^\"]+)\".*""").saveAs("entraFlowToken"))
            .check(regex("""(?s).*?\"canary\"\s*:\s*\"([^\"]+)\".*""").saveAs("entraCanary"))
        )
        .exec(
          http("Entra credential discovery")
            .post(s"$microsoftLoginBaseUrl/common/GetCredentialType?mkt=en-GB")
            .headers(Map(
              "Accept" -> "application/json, text/javascript, */*; q=0.01",
              "Content-Type" -> "application/json; charset=UTF-8",
              "Origin" -> microsoftLoginBaseUrl,
              "Referer" -> "#{entraAuthorizeUrl}",
              "canary" -> "#{entraCanary}",
              "hpgrequestid" -> "#{entraSessionId}"
            ))
            .body(StringBody(credentialDiscoveryBody)).asJson
            .check(status.is(200))
        )
        .exec(
          http("Entra username and password")
            .post(s"$microsoftLoginBaseUrl/$tenantId/login")
            .headers(Map(
              "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
              "Content-Type" -> "application/x-www-form-urlencoded",
              "Origin" -> microsoftLoginBaseUrl,
              "Referer" -> "#{entraAuthorizeUrl}"
            ))
            .formParam("i13", "0")
            .formParam("login", "#{username}")
            .formParam("loginfmt", "#{username}")
            .formParam("type", "11")
            .formParam("LoginOptions", "3")
            .formParam("lrt", "")
            .formParam("lrtPartition", "")
            .formParam("hisRegion", "")
            .formParam("hisScaleUnit", "")
            .formParam("passwd", password)
            .formParam("ps", "2")
            .formParam("psRNGCDefaultType", "")
            .formParam("psRNGCEntropy", "")
            .formParam("psRNGCSLK", "")
            .formParam("canary", "#{entraCanary}")
            .formParam("ctx", "#{entraContext}")
            .formParam("hpgrequestid", "#{entraSessionId}")
            .formParam("flowToken", "#{entraFlowToken}")
            .formParam("PPSX", "")
            .formParam("NewUser", "1")
            .formParam("FoundMSAs", "")
            .formParam("fspost", "0")
            .formParam("i21", "0")
            .formParam("CookieDisclosure", "0")
            .formParam("IsFidoSupported", "1")
            .formParam("isSignupPost", "0")
            .formParam("DfpArtifact", "")
            .formParam("i19", "3306")
            .check(status.is(200))
            .check(callbackUrlCheck)
        )
        .exec { session =>
          session("entraCallbackUrl").validate[String].map { callbackUrl =>
            val normalisedCallbackUrl = callbackUrl
              .replace("\\/", "/")
              .replace("&amp;", "&")
              .replace("\\u0026", "&")
            session.set(
              "entraCallbackUrl",
              if (normalisedCallbackUrl.startsWith("/")) Environment.baseURL + normalisedCallbackUrl
              else normalisedCallbackUrl
            )
          }
        }
        .exec(
          http("AppReg SSO callback")
            .get("#{entraCallbackUrl}")
            .headers(browserHeaders ++ Map("Referer" -> (microsoftLoginBaseUrl + "/")))
            .disableFollowRedirect
            .check(status.is(302))
        )
        .exec(
          http("AppReg authenticated home")
            .get("/")
            .headers(browserHeaders)
            .check(status.is(200))
        )
        .exec(
          http("AppReg session check")
            .get("/sso/me")
            .header("Accept", "application/json")
            .check(status.is(200))
            .check(jsonPath("$.authenticated").is("true"))
        )
    }
}
