package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.AddApplicationScenario;
import scenarios.ApplicationListCreateScenario;
import utils.Environment;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * One-user setup utility for recording ARCPOC-1617.
 * It creates an isolated list and three completed, unresulted Applications; it does not apply a Result.
 */
public class ResultMultipleApplicationsSetupSimulation extends Simulation {
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public ResultMultipleApplicationsSetupSimulation() {
    var httpProtocol = http
      .baseUrl(Environment.BASE_URL)
      .doNotTrackHeader("1")
      .inferHtmlResources()
      .silentResources();

    var setupApplications = scenario("AppReg multiple application result recording setup")
      .exitBlockOnFail().on(
        feed(ssoUserFeeder)
          .exec(SsoAuthentication.login())
          .exec(ApplicationListCreateScenario.createApplicationList())
          .exec(AddApplicationScenario.addApplication("entryIdOne"))
          .exec(AddApplicationScenario.addApplication("entryIdTwo"))
          .exec(AddApplicationScenario.addApplication("entryIdThree"))
      );

    setUp(setupApplications.injectOpen(atOnceUsers(1)))
      .protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }
}
