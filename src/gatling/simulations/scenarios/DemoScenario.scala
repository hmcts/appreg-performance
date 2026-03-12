package scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import utils.{Environment, Headers}

object DemoScenario {

  val BaseURL = Environment.baseURL

  val Homepage =

    group("Service_010_Homepage") {

      exec(http("Homepage")
        .get(BaseURL)
        .headers(Headers.commonHeader)
        .check(substring("Sign in or create an account")))

    }

}