package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.ActivityAuditReportScenario;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static utils.AppRegHttp.protocol;

/** One-user, non-destructive proof that generates and downloads an Activity Audit CSV report. */
public class ActivityAuditReportProofSimulation extends Simulation {
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public ActivityAuditReportProofSimulation() {
    var httpProtocol = protocol();
    var report = scenario("AppReg Activity Audit report proof").exitBlockOnFail().on(
      feed(ssoUserFeeder)
        .exec(SsoAuthentication.login())
        .exec(ActivityAuditReportScenario.generateActivityAuditReport()));

    setUp(report.injectOpen(atOnceUsers(1))).protocols(httpProtocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }
}
