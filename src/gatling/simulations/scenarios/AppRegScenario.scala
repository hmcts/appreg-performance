package scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import utils.{Environment, Headers}

object AppRegScenario {

  def applicationsList(authenticated: Boolean) = {
    val requestHeaders = if (authenticated) {
      Headers.commonHeader ++ Map("Authorization" -> "Bearer #{accessToken}")
    } else {
      Headers.commonHeader
    }

    group("AppReg_010_Applications_List") {
      exec(http("Applications list")
        .get(Environment.applicationsListPath)
        .headers(requestHeaders)
        .check(status.is(200))
        .check(substring("HMCTS Applications Register - Home - GOV.UK"))
        .check(regex("""<script src="([^"]*main-[^"]+\.js)"""").saveAs("mainScript")))
        .exec(http("Applications list JavaScript")
          .get("/#{mainScript}")
          .check(status.is(200)))
    }
      .exec(if (authenticated) {
        http("Get application lists")
          .get(s"${Environment.apiBaseURL}/application-lists")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "1")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Authorization", "Bearer #{accessToken}")
          .check(status.is(200))
          .check(headerRegex("Content-Type", ".*json.*"))
      } else {
        exec(session => session)
      })
  }
}
