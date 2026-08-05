package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.AddApplicationScenario;
import scenarios.ApplicationListCreateScenario;
import scenarios.SearchScenario;
import scenarios.UpdateApplicationScenario;
import utils.Environment;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

/** One-user proof for the destructive, curated Application update chain. */
public class UpdateApplicationProofSimulation extends Simulation {
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
          .exec(SearchScenario.searchApplicationLists())
          .exec(ApplicationListCreateScenario.createApplicationList())
          .exec(AddApplicationScenario.addApplication())
          .exec(UpdateApplicationScenario.updateApplication())
      );

    setUp(updateApplication.injectOpen(atOnceUsers(1)))
      .protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }
}
