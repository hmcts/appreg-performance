package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.ResultMultipleApplicationsScenario;
import utils.Environment;
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
import static utils.Environment.requiredEnvironmentVariable;
import static utils.Headers.COMMON_HEADER;

/** One-user proof that applies the same result to three isolated Applications in one list. */
public class ResultMultipleApplicationsProofSimulation extends Simulation {
  private static final String SEEDED_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_BULK_TARGET_LIST_ID");
  private static final String SEEDED_ENTRY_ID_ONE = requiredEnvironmentVariable("APPREG_SEED_BULK_ENTRY_ID_1");
  private static final String SEEDED_ENTRY_ID_TWO = requiredEnvironmentVariable("APPREG_SEED_BULK_ENTRY_ID_2");
  private static final String SEEDED_ENTRY_ID_THREE = requiredEnvironmentVariable("APPREG_SEED_BULK_ENTRY_ID_3");
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public ResultMultipleApplicationsProofSimulation() {
    var httpProtocol = protocol();

    var resultMultipleApplications = scenario("AppReg multiple application result proof")
      .exitBlockOnFail().on(
        feed(ssoUserFeeder)
          .exec(SsoAuthentication.login())
          .exec(http("Application lists page")
            .get("/applications-list")
            .headers(COMMON_HEADER)
            .check(status().is(200)))
          .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("xsrfToken")))
          .exec(session -> session
            .set("applicationListId", SEEDED_LIST_ID)
            .set("entryIdOne", SEEDED_ENTRY_ID_ONE)
            .set("entryIdTwo", SEEDED_ENTRY_ID_TWO)
            .set("entryIdThree", SEEDED_ENTRY_ID_THREE))
          .exec(ResultMultipleApplicationsScenario.resultMultipleApplications())
      );

    setUp(resultMultipleApplications.injectOpen(atOnceUsers(1)))
      .protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }
}
