package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.UpdateApplicationScenario;
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
import static utils.Headers.COMMON_HEADER;

/** One-user proof for the destructive, curated Application update chain. */
public class UpdateApplicationProofSimulation extends Simulation {
  private static final String SEEDED_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_EDITABLE_LIST_ID");
  private static final String SEEDED_ENTRY_ID = requiredEnvironmentVariable("APPREG_SEED_EDITABLE_ENTRY_ID");
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public UpdateApplicationProofSimulation() {
    var httpProtocol = http
      .baseUrl(Environment.BASE_URL)
      .doNotTrackHeader("1")
      .inferHtmlResources()
      .silentResources();

    var updateApplication = scenario("AppReg application update proof")
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
            .set("applicationEntryId", SEEDED_ENTRY_ID))
          .exec(UpdateApplicationScenario.updateApplication())
      );

    setUp(updateApplication.injectOpen(atOnceUsers(1)))
      .protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }

  private static String requiredEnvironmentVariable(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Set " + name + " to run the seeded application-update proof");
    }
    return value;
  }
}
