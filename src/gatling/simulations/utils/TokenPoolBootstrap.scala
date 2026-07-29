package utils

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.time.Duration
import scala.collection.immutable.Vector
import scala.util.Try

/** Obtains a bounded pool of dedicated test-user tokens before Gatling starts. */
object TokenPoolBootstrap {

  private val httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .build()

  private def environmentVariable(names: String*): Option[String] =
    names.iterator.map(System.getenv).find(value => value != null && value.nonEmpty)

  private def requiredEnvironmentVariable(names: String*): String =
    environmentVariable(names: _*).getOrElse(
      throw new IllegalArgumentException(s"Set one of: ${names.mkString(", ")}")
    )

  private def integerEnvironmentVariable(name: String, default: Int): Int =
    environmentVariable(name).map { value =>
      Try(value.toInt).getOrElse(throw new IllegalArgumentException(s"$name must be an integer"))
    }.getOrElse(default)

  private def tokenEndpoint: String =
    environmentVariable("APPREG_TOKEN_ENDPOINT").getOrElse {
      val tenantId = requiredEnvironmentVariable("APPREG_TENANT_ID", "TENANT_ID")
      s"https://login.microsoftonline.com/$tenantId/oauth2/v2.0/token"
    }

  private def cursorFile: Path = Paths.get(
    environmentVariable("APPREG_ACCOUNT_CURSOR_FILE").getOrElse("build/appreg-token-pool.next-index")
  )

  private def nextAccountIndex(): Int = {
    val file = cursorFile
    Option(file.getParent).foreach(parent => Files.createDirectories(parent))
    if (Files.exists(file)) {
      val value = Files.readString(file, StandardCharsets.UTF_8).trim
      Try(value.toInt).getOrElse(
        throw new IllegalArgumentException(s"Account cursor file $file does not contain a numeric index")
      )
    } else {
      integerEnvironmentVariable("APPREG_ACCOUNT_START_INDEX", 1)
    }
  }

  private def saveNextAccountIndex(nextIndex: Int): Unit =
    Files.writeString(
      cursorFile,
      s"$nextIndex\n",
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )

  private def accountName(template: String, index: Int): String = {
    if (!template.contains("{index}")) {
      throw new IllegalArgumentException("TEST_USER_EMAIL or APPREG_TEST_ACCOUNT_TEMPLATE must contain {index}")
    }
    template.replace("{index}", index.toString)
  }

  private def formEncode(values: Seq[(String, String)]): String =
    values.map { case (key, value) =>
      s"${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
    }.mkString("&")

  private def accessToken(responseBody: String): Option[String] =
    "\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"".r
      .findFirstMatchIn(responseBody)
      .map(_.group(1))

  private def retryAfterMillis(response: HttpResponse[String], fallbackMillis: Int): Long = {
    val retryAfter = response.headers().firstValue("Retry-After")
    if (retryAfter.isPresent) Try(retryAfter.get().toLong).toOption.map(_ * 1000L).getOrElse(fallbackMillis.toLong)
    else fallbackMillis.toLong
  }

  private def requestToken(username: String, accountIndex: Int, requestIntervalMillis: Int, maxRetries: Int): String = {
    val clientId = requiredEnvironmentVariable("APPREG_CLIENT_ID", "CLIENT_ID")
    val clientSecret = requiredEnvironmentVariable("APPREG_CLIENT_SECRET", "CLIENT_SECRET")
    val scope = requiredEnvironmentVariable("APPREG_TOKEN_SCOPE", "SCOPE")
    val password = requiredEnvironmentVariable("APPREG_TEST_USER_PASSWORD", "TEST_USERS_PASSWORD")
    val requestBody = formEncode(Seq(
      "grant_type" -> "password",
      "client_id" -> clientId,
      "client_secret" -> clientSecret,
      "scope" -> scope,
      "username" -> username,
      "password" -> password
    ))

    (0 to maxRetries).iterator.map { attempt =>
      val request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() == 200) {
        accessToken(response.body()).getOrElse(
          throw new IllegalStateException("Token endpoint returned 200 without an access token")
        )
      } else if ((response.statusCode() == 429 || response.statusCode() >= 500) && attempt < maxRetries) {
        Thread.sleep(retryAfterMillis(response, requestIntervalMillis * (1 << attempt)))
        null
      } else {
        throw new IllegalStateException(s"Token request failed with HTTP ${response.statusCode()} for account index $accountIndex")
      }
    }.find(_ != null).getOrElse(throw new IllegalStateException("Token request retries were exhausted"))
  }

  /** Tokens remain in memory. The cursor stores only the next account index. */
  def fetch(accountCount: Int): Vector[String] = {
    require(accountCount > 0, "The token-pool account count must be greater than zero")

    val template = requiredEnvironmentVariable("APPREG_TEST_ACCOUNT_TEMPLATE", "TEST_USER_EMAIL")
    val intervalMillis = integerEnvironmentVariable("APPREG_TOKEN_REQUEST_INTERVAL_MILLIS", 1000)
    val maxRetries = integerEnvironmentVariable("APPREG_TOKEN_MAX_RETRIES", 3)
    require(intervalMillis >= 0, "APPREG_TOKEN_REQUEST_INTERVAL_MILLIS must not be negative")
    require(maxRetries >= 0, "APPREG_TOKEN_MAX_RETRIES must not be negative")

    val firstIndex = nextAccountIndex()
    val tokens = (firstIndex until firstIndex + accountCount).zipWithIndex.map { case (index, offset) =>
      if (offset > 0 && intervalMillis > 0) Thread.sleep(intervalMillis.toLong)
      val token = requestToken(accountName(template, index), index, intervalMillis, maxRetries)
      saveNextAccountIndex(index + 1)
      token
    }.toVector

    println(s"Prepared ${tokens.size} AppReg test-user token(s), starting at account index $firstIndex")
    tokens
  }
}
