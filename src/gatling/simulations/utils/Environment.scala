package utils

object Environment {

  private val defaultBaseURL = "https://appreg.test.apps.hmcts.net"

  val baseURL = Option(System.getenv("TEST_URL"))
    .filter(_.nonEmpty)
    .map(_.stripSuffix("/"))
    .getOrElse(defaultBaseURL)

  val applicationsListPath = "/applications-list"

  def apiBaseURL: String = Option(System.getenv("APPREG_API_BASE_URL"))
    .filter(_.nonEmpty)
    .map(_.stripSuffix("/"))
    .getOrElse {
      if (baseURL.contains("demo")) {
        "https://appreg-api.demo.platform.hmcts.net"
      } else if (baseURL.contains("staging") || baseURL.contains("stg")) {
        "https://appreg-api.staging.platform.hmcts.net"
      } else {
        throw new IllegalArgumentException(
          "Set APPREG_API_BASE_URL when running an authenticated journey against a non-demo or non-staging target"
        )
      }
    }

  val minThinkTime = 5
  val maxThinkTime = 7

}
