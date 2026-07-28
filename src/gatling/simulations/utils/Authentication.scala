package utils

import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._

object Authentication {

  private def environmentVariable(names: String*): Option[String] =
    names.iterator.map(System.getenv).find(value => value != null && value.nonEmpty)

  private def requiredEnvironmentVariable(names: String*): String =
    environmentVariable(names: _*).getOrElse(
      throw new IllegalArgumentException(s"Set one of: ${names.mkString(", ")}")
    )

  private def tokenEndpoint: String =
    environmentVariable("APPREG_TOKEN_ENDPOINT").getOrElse {
      val tenantId = requiredEnvironmentVariable("APPREG_TENANT_ID", "TENANT_ID")
      s"https://login.microsoftonline.com/$tenantId/oauth2/v2.0/token"
    }

  private def usePreAcquiredTokenOr(requestToken: => ChainBuilder) =
    environmentVariable("APPREG_ACCESS_TOKEN") match {
      case Some(accessToken) => exec(session => session.set("accessToken", accessToken))
      case None => requestToken
    }

  /**
    * Uses a pre-acquired service token when APPREG_ACCESS_TOKEN is supplied.
    * Otherwise, obtains a token for this virtual user from the runtime-supplied
    * token endpoint. The pre-acquired-token option is preferred at scale so the
    * identity provider is not included in the load profile.
    */
  def clientCredentials = {
    usePreAcquiredTokenOr(requestClientCredentialsToken())
  }

  private def requestClientCredentialsToken() = {
    val clientId = requiredEnvironmentVariable("APPREG_CLIENT_ID", "CLIENT_ID")
    val clientSecret = requiredEnvironmentVariable("APPREG_CLIENT_SECRET", "CLIENT_SECRET")
    val scope = requiredEnvironmentVariable("APPREG_TOKEN_SCOPE", "SCOPE")

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

  /**
    * Temporary local proof-of-concept mode. It mirrors the existing frontend
    * API-test setup and must only use a dedicated non-production test identity.
    */
  def passwordGrant = {
    usePreAcquiredTokenOr(requestPasswordGrantToken())
  }

  private def requestPasswordGrantToken() = {
    val clientId = requiredEnvironmentVariable("APPREG_CLIENT_ID", "CLIENT_ID")
    val clientSecret = requiredEnvironmentVariable("APPREG_CLIENT_SECRET", "CLIENT_SECRET")
    val scope = requiredEnvironmentVariable("APPREG_TOKEN_SCOPE", "SCOPE")
    val username = requiredEnvironmentVariable("APPREG_TEST_USER_EMAIL", "TEST_USER1_EMAIL")
    val password = requiredEnvironmentVariable("APPREG_TEST_USER_PASSWORD", "TEST_USERS_PASSWORD")

    exec(
      http("Obtain AppReg test-user token")
        .post(tokenEndpoint)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .formParam("grant_type", "password")
        .formParam("client_id", clientId)
        .formParam("client_secret", clientSecret)
        .formParam("scope", scope)
        .formParam("username", username)
        .formParam("password", password)
        .check(status.is(200))
        .check(jsonPath("$.access_token").saveAs("accessToken"))
    )
  }
}
