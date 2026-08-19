package simulations;

import io.gatling.javaapi.core.Simulation;
import scenarios.SearchScenario;
import utils.LoginPreflightProfile;
import utils.LoginPreflightRetryQueue;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static utils.AppRegHttp.protocol;
import static utils.Headers.XSRF_TOKEN_COOKIE;

/**
 * Read-only preflight for the complete SSO and AppReg session path. It deliberately uses a
 * conservative one-account-per-second ramp before any destructive performance workload is attempted.
 */
public class AppRegLoginPreflightSimulation extends Simulation {
  private final LoginPreflightProfile profile = LoginPreflightProfile.fromRuntime();

  public AppRegLoginPreflightSimulation() {
    var httpProtocol = protocol();
    var users = SsoAuthentication.users(profile.concurrentUsers());
    var loginPreflight = scenario("AppReg login preflight")
      .exitBlockOnFail().on(
        feed(users)
          .exec(SsoAuthentication.login())
          .exec(getCookieValue(CookieKey(XSRF_TOKEN_COOKIE).saveAs("xsrfToken")))
          // A read-only application request confirms the authenticated session is usable.
          .exec(SearchScenario.searchApplicationLists())
      )
      .exec(LoginPreflightRetryQueue::retainFailedAccount);

    setUp(loginPreflight.injectOpen(
        rampUsers(profile.concurrentUsers()).during(profile.loginRampUpSeconds())
      ))
      .protocols(httpProtocol);
  }

  @Override
  public void before() {
    System.out.println("Login preflight: " + profile.concurrentUsers() + " accounts over "
        + profile.loginRampUpSeconds() + " seconds (one login per second maximum).");
  }

  @Override
  public void after() {
    LoginPreflightRetryQueue.write();
  }
}
