package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.List;
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

/** Uses only the required spare accounts to replace failed primary preflight accounts. */
public class AppRegLoginPreflightSpareSimulation extends Simulation {
  private final LoginPreflightProfile profile = LoginPreflightProfile.fromRuntime();

  public AppRegLoginPreflightSpareSimulation() {
    List<LoginPreflightRetryQueue.Account> failedPrimaryAccounts = LoginPreflightRetryQueue.read(
        LoginPreflightRetryQueue.PRIMARY_FAILURES_PATH);
    if (failedPrimaryAccounts.isEmpty()) {
      throw new IllegalArgumentException("Login preflight has no failed primary accounts to replace");
    }

    var spare = scenario("AppReg login preflight spare accounts")
      .exitBlockOnFail().on(
        feed(SsoAuthentication.spareUsers(failedPrimaryAccounts, profile.concurrentUsers()))
          .exec(SsoAuthentication.login())
          .exec(getCookieValue(CookieKey(XSRF_TOKEN_COOKIE).saveAs("xsrfToken")))
          .exec(SearchScenario.searchApplicationLists())
      )
      .exec(LoginPreflightRetryQueue::retainFailedSpare);

    setUp(spare.injectOpen(rampUsers(failedPrimaryAccounts.size()).during(
        profile.rampDurationFor(failedPrimaryAccounts.size()))))
      .protocols(protocol());
  }

  @Override
  public void before() {
    System.out.println("Login preflight spare phase: replacing failed primary accounts at the configured rate.");
  }

  @Override
  public void after() {
    LoginPreflightRetryQueue.writeRetryFailures();
  }
}
