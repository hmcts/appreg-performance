package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.AddApplicationScenario;
import scenarios.ApplicationListCreateScenario;
import scenarios.BulkUpdateOfficialsScenario;
import utils.Environment;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

/** One-user proof that bulk-updates officials on three isolated Applications in one list. */
public class BulkUpdateOfficialsProofSimulation extends Simulation {
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public BulkUpdateOfficialsProofSimulation() {
    var httpProtocol = http
      .baseUrl(Environment.BASE_URL)
      .doNotTrackHeader("1")
      .inferHtmlResources()
      .silentResources();

    var bulkUpdateOfficials = scenario("AppReg bulk update officials proof")
      .exitBlockOnFail().on(
        feed(ssoUserFeeder)
          .exec(SsoAuthentication.login())
          .exec(ApplicationListCreateScenario.createApplicationList())
          .exec(AddApplicationScenario.addApplication("entryIdOne"))
          .exec(AddApplicationScenario.addApplication("entryIdTwo"))
          .exec(AddApplicationScenario.addApplication("entryIdThree"))
          .exec(BulkUpdateOfficialsScenario.bulkUpdateOfficials())
      );

    setUp(bulkUpdateOfficials.injectOpen(atOnceUsers(1)))
      .protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }
}
