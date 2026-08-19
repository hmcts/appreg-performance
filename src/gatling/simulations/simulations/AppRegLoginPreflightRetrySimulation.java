package simulations;

import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.Simulation;
import scenarios.SearchScenario;
import utils.LoginPreflightRetryQueue;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.exitHereIfFailed;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static utils.AppRegHttp.protocol;
import static utils.Headers.XSRF_TOKEN_COOKIE;

/** Retries, once and only after the initial preflight, the accounts in its workspace-only queue. */
public class AppRegLoginPreflightRetrySimulation extends Simulation {
  public AppRegLoginPreflightRetrySimulation() {
    FeederBuilder.FileBased<String> failedUsers = csv(LoginPreflightRetryQueue.RETRY_PATH.toString()).queue();
    int retryCount = failedUsers.recordsCount();
    if (retryCount < 1) {
      throw new IllegalArgumentException("Login preflight retry queue contains no failed accounts");
    }

    var retry = scenario("AppReg login preflight retry")
      .feed(failedUsers)
      .exitBlockOnFail().on(
        SsoAuthentication.login()
          .exec(getCookieValue(CookieKey(XSRF_TOKEN_COOKIE).saveAs("xsrfToken")))
          .exec(SearchScenario.searchApplicationLists())
      )
      .exitHereIfFailed();

    setUp(retry.injectOpen(rampUsers(retryCount).during(retryCount)))
      .protocols(protocol())
      .assertions(global().successfulRequests().percent().gte(100.0));
  }

  @Override
  public void before() {
    System.out.println("Login preflight retry: retrying each failed account once at one login per second.");
  }
}
