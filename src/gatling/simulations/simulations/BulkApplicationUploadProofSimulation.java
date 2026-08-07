package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.ApplicationListCreateScenario;
import scenarios.BulkApplicationUploadScenario;
import utils.Environment;
import utils.SsoAuthentication;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;

/** One-user proof that uploads the approved two-entry CSV into an isolated Application List. */
public class BulkApplicationUploadProofSimulation extends Simulation {
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);
  public BulkApplicationUploadProofSimulation() {
    var protocol = http.baseUrl(Environment.BASE_URL).doNotTrackHeader("1").inferHtmlResources().silentResources();
    var upload = scenario("AppReg bulk application upload proof").exitBlockOnFail().on(
      feed(ssoUserFeeder).exec(SsoAuthentication.login()).exec(ApplicationListCreateScenario.createApplicationList())
        .exec(BulkApplicationUploadScenario.bulkUploadApplications()));
    setUp(upload.injectOpen(atOnceUsers(1))).protocols(protocol).assertions(global().successfulRequests().percent().gte(100.0));
  }
}
