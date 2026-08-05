package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.AddApplicationScenario;
import scenarios.ApplicationListCreateScenario;
import scenarios.ApplicationResultSeedScenario;
import scenarios.UpdateApplicationResultScenario;
import utils.Environment;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

/** One-user proof that seeds an existing Result, then replays the recorded UI update flow. */
public class UpdateApplicationResultProofSimulation extends Simulation {
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public UpdateApplicationResultProofSimulation() {
    var httpProtocol = http
      .baseUrl(Environment.BASE_URL)
      .doNotTrackHeader("1")
      .inferHtmlResources()
      .silentResources();

    var updateApplicationResult = scenario("AppReg application result update proof")
      .exitBlockOnFail().on(
        feed(ssoUserFeeder)
          .exec(SsoAuthentication.login())
          .exec(ApplicationListCreateScenario.createApplicationList())
          .exec(AddApplicationScenario.addApplication())
          .exec(ApplicationResultSeedScenario.createExistingResult())
          .exec(UpdateApplicationResultScenario.updateApplicationResult())
      );

    setUp(updateApplicationResult.injectOpen(atOnceUsers(1)))
      .protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }
}
