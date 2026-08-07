package simulations;

import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.ActivityAuditReportScenario;
import utils.Environment;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

/** One-user, non-destructive proof that generates and downloads an Activity Audit CSV report. */
public class ActivityAuditReportProofSimulation extends Simulation {
  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public ActivityAuditReportProofSimulation() {
    var protocol = http.baseUrl(Environment.BASE_URL).doNotTrackHeader("1").inferHtmlResources().silentResources();
    var report = scenario("AppReg Activity Audit report proof").exitBlockOnFail().on(
      feed(ssoUserFeeder)
        .exec(SsoAuthentication.login())
        .exec(ActivityAuditReportScenario.generateActivityAuditReport()));

    setUp(report.injectOpen(atOnceUsers(1))).protocols(protocol)
      .assertions(global().successfulRequests().percent().gte(100.0));
  }
}
