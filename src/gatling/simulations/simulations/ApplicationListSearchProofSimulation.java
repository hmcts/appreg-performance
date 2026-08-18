package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.SearchScenario;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static utils.AppRegHttp.protocol;

/** One-user proof for validating the cleaned Application List search chain. */
public class ApplicationListSearchProofSimulation extends Simulation {
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public ApplicationListSearchProofSimulation() {
    var httpProtocol = protocol();

    var searchApplicationLists = scenario("AppReg application list search proof")
      .exitBlockOnFail().on(
        feed(ssoUserFeeder)
          .exec(SsoAuthentication.login())
          .exec(SearchScenario.searchApplicationLists())
      );

    setUp(searchApplicationLists.injectOpen(atOnceUsers(1)))
      .protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }
}
