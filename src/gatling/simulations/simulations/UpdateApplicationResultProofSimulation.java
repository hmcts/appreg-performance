package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.UpdateApplicationResultScenario;
import utils.Environment;
import utils.AuthenticationStage;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.AppRegHttp.protocol;
import static utils.Headers.XSRF_TOKEN_COOKIE;
import static utils.Environment.requiredEnvironmentVariable;
import static utils.Headers.COMMON_HEADER;

/** One-user proof that updates a Result on one allocated Application. */
public class UpdateApplicationResultProofSimulation extends Simulation {
  private static final String SEEDED_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_UPDATE_RESULT_LIST_ID");
  private static final String SEEDED_ENTRY_ID = requiredEnvironmentVariable("APPREG_SEED_UPDATE_RESULT_ENTRY_ID");
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public UpdateApplicationResultProofSimulation() {
    var httpProtocol = protocol();

    var updateApplicationResult = scenario("AppReg application result update proof")
      .exitBlockOnFail().on(
        feed(ssoUserFeeder)
          .exec(AuthenticationStage.authenticateFrameworkProof())
          .exec(http("Application lists page")
            .get("/applications-list")
            .headers(COMMON_HEADER)
            .check(status().is(200)))
          .exec(getCookieValue(CookieKey(XSRF_TOKEN_COOKIE).saveAs("xsrfToken")))
          .exec(session -> session
            .set("applicationListId", SEEDED_LIST_ID)
            .set("applicationEntryId", SEEDED_ENTRY_ID))
          .exec(UpdateApplicationResultScenario.updateApplicationResult())
      );

    setUp(updateApplicationResult.injectOpen(atOnceUsers(1)))
      .protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }
}
