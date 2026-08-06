package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.AddApplicationScenario;
import scenarios.ApplicationListCreateScenario;
import scenarios.ResultApplicationScenario;
import utils.Environment;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

/** One-user proof that applies a Result to one isolated, previously unresulted Application. */
public class ResultApplicationProofSimulation extends Simulation {
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public ResultApplicationProofSimulation() {
    var httpProtocol = http
      .baseUrl(Environment.BASE_URL)
      .doNotTrackHeader("1")
      .inferHtmlResources()
      .silentResources();

    var resultApplication = scenario("AppReg application result proof")
      .exitBlockOnFail().on(
        feed(ssoUserFeeder)
          .exec(SsoAuthentication.login())
          .exec(ApplicationListCreateScenario.createApplicationList())
          .exec(AddApplicationScenario.addApplication())
          .exec(ResultApplicationScenario.resultApplication())
      );

    setUp(resultApplication.injectOpen(atOnceUsers(1)))
      .protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }
}
