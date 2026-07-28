package utils

import io.gatling.core.Predef._
import io.gatling.http.Predef._

object Authentication {

  private def requiredEnvironmentVariable(name: String): String =
    Option(System.getenv(name)).filter(_.nonEmpty).getOrElse(
      throw new IllegalArgumentException(s"Environment variable $name must be set when authMode=client-credentials")
    )

  /**
    * Uses a pre-acquired service token when APPREG_ACCESS_TOKEN is supplied.
    * Otherwise, obtains a token for this virtual user from the runtime-supplied
    * token endpoint. The pre-acquired-token option is preferred at scale so the
    * identity provider is not included in the load profile.
    */
  def clientCredentials = {
    Option(System.getenv("APPREG_ACCESS_TOKEN")).filter(_.nonEmpty) match {
      case Some(accessToken) => exec(session => session.set("accessToken", accessToken))
      case None => requestClientCredentialsToken()
    }
  }

  private def requestClientCredentialsToken() = {
    val tokenEndpoint = requiredEnvironmentVariable("APPREG_TOKEN_ENDPOINT")
    val clientId = requiredEnvironmentVariable("APPREG_CLIENT_ID")
    val clientSecret = requiredEnvironmentVariable("APPREG_CLIENT_SECRET")
    val scope = requiredEnvironmentVariable("APPREG_TOKEN_SCOPE")

    exec(
      http("Obtain AppReg service token")
        .post(tokenEndpoint)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .formParam("grant_type", "client_credentials")
        .formParam("client_id", clientId)
        .formParam("client_secret", clientSecret)
        .formParam("scope", scope)
        .check(status.is(200))
        .check(jsonPath("$.access_token").saveAs("accessToken"))
    )
  }
}
