package scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import utils.{Environment, Headers}

object AppRegScenario {

  def applicationsList(sessionAuthenticated: Boolean) = {
    group("AppReg_010_Applications_List") {
      exec(http("Applications list")
        .get(Environment.applicationsListPath)
        .headers(Headers.commonHeader)
        .check(status.is(200))
        .check(substring("HMCTS Applications Register - Home - GOV.UK"))
        .check(regex("""<script src="([^"]*main-[^"]+\.js)"""").saveAs("mainScript")))
        .exec(http("Applications list JavaScript")
          .get("/#{mainScript}")
          .check(status.is(200)))
    }
      .exec(if (sessionAuthenticated) {
        val dataRequest = http("Get application lists")
          .get("/application-lists")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "1")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status.is(200))
          .check(headerRegex("Content-Type", ".*json.*"))
        dataRequest
      } else {
        exec(session => session)
      })
  }
}
