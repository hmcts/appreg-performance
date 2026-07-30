package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.core.scenario.Simulation
import io.gatling.core.controller.inject.open.OpenInjectionStep
import io.gatling.commons.stats.assertion.Assertion
import io.gatling.core.pause.PauseType
import scenarios._
import utils.{Environment, SsoAuthentication}

import scala.concurrent.duration._

class AppRegSimulation extends Simulation {

  /* TEST TYPE DEFINITION */
  /* pipeline = nightly pipeline against the perftest/AAT environment (configure the Jenkins_nightly file) */
  /* perftest (default) = performance test against the perftest environment */
  val testType = scala.util.Properties.envOrElse("TEST_TYPE", "perftest")

  //set the environment based on the test type
  val environment = testType match{
    case "perftest" => "perftest"
    case "pipeline" => "perftest" //define whether to run the Jenkins nightly pipeline against perftest or aat
    case _ => "**INVALID**"
  }
  /* ******************************** */

  /* ADDITIONAL COMMAND LINE ARGUMENT OPTIONS */
  val debugMode = System.getProperty("debug", "off") //runs a single user e.g. ./gradle gatlingRun -Ddebug=on (default: off)
  val env = System.getProperty("env", environment) //manually override the environment aat|perftest e.g. ./gradle gatlingRun -Denv=aat
  val authMode = System.getProperty(
    "authMode",
    scala.util.Properties.envOrElse("AUTH_MODE", "none")
  ) // none|sso-login
  /* ******************************** */

  /* PERFORMANCE TEST CONFIGURATION */
  val rampUpDurationMins = 5
  val rampDownDurationMins = 5
  val testDurationMins = 60

  val hourlyTarget:Double = 10
  val ratePerSec = hourlyTarget / 3600

  //If running in debug mode, disable pauses between steps
  val pauseOption:PauseType = debugMode match{
    case "off" => constantPauses
    case _ => disabledPauses
  }
  /* ******************************** */

  /* PIPELINE CONFIGURATION */
  val numberOfPipelineUsers:Double = scala.util.Properties
    .envOrElse("PERFORMANCE_TEST_USERS", "1")
    .toDouble
  /* ******************************** */

  // One dedicated test account is allocated per SSO virtual user.
  // A manual override permits a profile to reserve a larger, known account range.
  val requiredAccountCount: Int = Option(System.getProperty("accountCount"))
    .map(_.toInt)
    .getOrElse(testType match {
      case "pipeline" => numberOfPipelineUsers.toInt
      case "perftest" if debugMode == "on" => 1
      case "perftest" => math.ceil(ratePerSec * ((rampUpDurationMins + testDurationMins + rampDownDurationMins) * 60 - ((rampUpDurationMins + rampDownDurationMins) * 30))).toInt
      case _ => 1
    })

  val ssoUserFeeder = if (authMode == "sso-login") SsoAuthentication.users(requiredAccountCount) else Iterator.empty

  val httpProtocol = http
    .baseUrl(Environment.baseURL)
    .doNotTrackHeader("1")
    .inferHtmlResources()
    .silentResources

  before{
    println(s"Test Type: ${testType}")
    println(s"Test Environment: ${env}")
    println(s"Debug Mode: ${debugMode}")
    println(s"Authentication Mode: ${authMode}")
    if (authMode == "sso-login") println(s"SSO Login Accounts: $requiredAccountCount")
  }

  val applicationsListScenario = scenario("AppReg applications list")
    .exitBlockOnFail {
      exec(_.set("env", s"${env}"))
      .exec(authMode match {
        case "none" => exec(session => session)
        case "sso-login" => feed(ssoUserFeeder).exec(SsoAuthentication.login)
        case _ => throw new IllegalArgumentException("authMode must be none or sso-login")
      })
      .exec(AppRegScenario.applicationsList(
        sessionAuthenticated = authMode == "sso-login"
      ))
    }

  //defines the Gatling simulation model, based on the inputs
  def simulationProfile(simulationType: String, userPerSecRate: Double, numberOfPipelineUsers: Double): Seq[OpenInjectionStep] = {
    simulationType match {
      case "perftest" =>
        if (debugMode == "off") {
          Seq(
            rampUsersPerSec(0.00) to (userPerSecRate) during (rampUpDurationMins.minutes),
            constantUsersPerSec(userPerSecRate) during (testDurationMins.minutes),
            rampUsersPerSec(userPerSecRate) to (0.00) during (rampDownDurationMins.minutes)
          )
        }
        else{
          Seq(atOnceUsers(1))
        }
      case "pipeline" =>
        Seq(rampUsers(numberOfPipelineUsers.toInt) during (2.minutes))
      case _ =>
        Seq(nothingFor(0))
    }
  }

  //defines the test assertions, based on the test type
  def assertions(simulationType: String): Seq[Assertion] = {
    simulationType match {
      case "perftest" =>
        Seq(global.successfulRequests.percent.gte(95))
      case "pipeline" =>
        Seq(global.successfulRequests.percent.gte(95),
        )
      case _ =>
        Seq()
    }
  }

  setUp(
    applicationsListScenario.inject(simulationProfile(testType, ratePerSec, numberOfPipelineUsers)).pauses(pauseOption)
  ).protocols(httpProtocol)
  .assertions(assertions(testType))

}
