package utils

object Environment {

  private val defaultBaseURL = "https://appreg.test.apps.hmcts.net"

  val baseURL = Option(System.getenv("TEST_URL"))
    .filter(_.nonEmpty)
    .map(_.stripSuffix("/"))
    .getOrElse(defaultBaseURL)

  val applicationsListPath = "/applications-list"

}
