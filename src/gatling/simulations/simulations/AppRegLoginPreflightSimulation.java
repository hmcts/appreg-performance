package simulations;

import io.gatling.javaapi.core.Simulation;
import scenarios.SearchScenario;
import utils.Environment;
import utils.LoginPreflightProfile;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * Read-only preflight for the complete SSO and AppReg session path. It deliberately uses a
 * conservative one-account-per-second ramp before any destructive initial workload is attempted.
 */
public class AppRegLoginPreflightSimulation extends Simulation {
  private final LoginPreflightProfile profile = LoginPreflightProfile.fromRuntime();

  public AppRegLoginPreflightSimulation() {
    var httpProtocol = http.baseUrl(Environment.BASE_URL).doNotTrackHeader("1").inferHtmlResources().silentResources();
    var users = SsoAuthentication.users(profile.concurrentUsers());
    var loginPreflight = scenario("AppReg login preflight")
      .exitBlockOnFail().on(
        feed(users)
          .exec(SsoAuthentication.login())
          .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("xsrfToken")))
          // A read-only application request confirms the authenticated session is usable.
          .exec(SearchScenario.searchApplicationLists())
      );

    setUp(loginPreflight.injectOpen(
        rampUsers(profile.concurrentUsers()).during(profile.loginRampUpSeconds())
      ))
      .protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }

  @Override
  public void before() {
    System.out.println("Login preflight: " + profile.concurrentUsers() + " accounts over "
        + profile.loginRampUpSeconds() + " seconds (one login per second maximum).");
  }
}
