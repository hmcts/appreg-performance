package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.BulkApplicationUploadScenario;
import utils.Environment;
import utils.SsoAuthentication;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.Headers.COMMON_HEADER;

/** One-user proof that uploads the approved CSV into an allocated seeded Application List. */
public class BulkApplicationUploadProofSimulation extends Simulation {
  private static final String SEEDED_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_BULK_UPLOAD_LIST_ID");
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);
  public BulkApplicationUploadProofSimulation() {
    var protocol = http.baseUrl(Environment.BASE_URL).doNotTrackHeader("1").inferHtmlResources().silentResources();
    var upload = scenario("AppReg bulk application upload proof").exitBlockOnFail().on(
      feed(ssoUserFeeder).exec(SsoAuthentication.login())
        .exec(http("Application lists page").get("/applications-list").headers(COMMON_HEADER).check(status().is(200)))
        .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("xsrfToken")))
        .exec(session -> session.set("applicationListId", SEEDED_LIST_ID))
        .exec(BulkApplicationUploadScenario.bulkUploadApplications()));
    setUp(upload.injectOpen(atOnceUsers(1))).protocols(protocol).assertions(global().successfulRequests().percent().gte(100.0));
  }
  private static String requiredEnvironmentVariable(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name + " to run the seeded bulk-upload proof");
    return value;
  }
}
