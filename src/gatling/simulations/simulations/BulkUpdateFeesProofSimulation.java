package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.AddApplicationScenario;
import scenarios.ApplicationListCreateScenario;
import scenarios.BulkUpdateFeesScenario;
import utils.Environment;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;

/** One-user proof that bulk-updates fee details on three isolated Applications. */
public class BulkUpdateFeesProofSimulation extends Simulation {
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public BulkUpdateFeesProofSimulation() {
    var httpProtocol = http.baseUrl(Environment.BASE_URL).doNotTrackHeader("1").inferHtmlResources().silentResources();
    var bulkUpdateFees = scenario("AppReg bulk update fees proof").exitBlockOnFail().on(
      feed(ssoUserFeeder).exec(SsoAuthentication.login())
        .exec(ApplicationListCreateScenario.createApplicationList())
        .exec(AddApplicationScenario.addApplication("entryIdOne"))
        .exec(AddApplicationScenario.addApplication("entryIdTwo"))
        .exec(AddApplicationScenario.addApplication("entryIdThree"))
        .exec(BulkUpdateFeesScenario.bulkUpdateFees()));
    setUp(bulkUpdateFees.injectOpen(atOnceUsers(1))).protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }
}
