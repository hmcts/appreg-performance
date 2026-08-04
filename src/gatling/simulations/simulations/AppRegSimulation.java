package simulations;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.PauseType;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import scenarios.AppRegScenario;
import utils.Environment;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;

public class AppRegSimulation extends Simulation {
  private final String testType = System.getenv().getOrDefault("TEST_TYPE", "perftest");
  private final String environment = switch (testType) {
    case "perftest", "pipeline" -> "perftest";
    default -> "**INVALID**";
  };
  private final String debugMode = System.getProperty("debug", "off");
  private final String env = System.getProperty("env", environment);
  private final String authMode = System.getProperty("authMode", System.getenv().getOrDefault("AUTH_MODE", "none"));

  private final int rampUpDurationMins = 5;
  private final int rampDownDurationMins = 5;
  private final int testDurationMins = 60;
  private final double hourlyTarget = 10;
  private final double ratePerSec = hourlyTarget / 3600;
  private final PauseType pauseOption = "off".equals(debugMode) ? constantPauses : disabledPauses;
  private final double numberOfPipelineUsers = Double.parseDouble(System.getenv().getOrDefault("PERFORMANCE_TEST_USERS", "1"));
  private final int requiredAccountCount = requiredAccountCount();
  private final Iterator<Map<String, Object>> ssoUserFeeder = "sso-login".equals(authMode)
    ? SsoAuthentication.users(requiredAccountCount) : List.<Map<String, Object>>of().iterator();

  public AppRegSimulation() {
    var httpProtocol = http.baseUrl(Environment.BASE_URL).doNotTrackHeader("1").inferHtmlResources().silentResources();
    ChainBuilder authentication = switch (authMode) {
      case "none" -> exec(session -> session);
      case "sso-login" -> feed(ssoUserFeeder).exec(SsoAuthentication.login());
      default -> throw new IllegalArgumentException("authMode must be none or sso-login");
    };
    ScenarioBuilder applicationsListScenario = scenario("AppReg applications list")
      .exitBlockOnFail().on(exec(session -> session.set("env", env))
        .exec(authentication)
        .exec(AppRegScenario.applicationsList("sso-login".equals(authMode))));

    setUp(applicationsListScenario.injectOpen(simulationProfile()).pauses(pauseOption))
      .protocols(httpProtocol)
      .assertions(assertions());
  }

  private int requiredAccountCount() {
    String override = System.getProperty("accountCount");
    if (override != null) return Integer.parseInt(override);
    if ("pipeline".equals(testType)) return (int) numberOfPipelineUsers;
    if ("perftest".equals(testType) && "on".equals(debugMode)) return 1;
    if ("perftest".equals(testType)) {
      return (int) Math.ceil(ratePerSec * ((rampUpDurationMins + testDurationMins + rampDownDurationMins) * 60 - ((rampUpDurationMins + rampDownDurationMins) * 30)));
    }
    return 1;
  }

  private List<OpenInjectionStep> simulationProfile() {
    return switch (testType) {
      case "perftest" -> "off".equals(debugMode) ? List.of(
        rampUsersPerSec(0).to(ratePerSec).during(Duration.ofMinutes(rampUpDurationMins)),
        constantUsersPerSec(ratePerSec).during(Duration.ofMinutes(testDurationMins)),
        rampUsersPerSec(ratePerSec).to(0).during(Duration.ofMinutes(rampDownDurationMins))
      ) : List.of(atOnceUsers(1));
      case "pipeline" -> List.of(rampUsers((int) numberOfPipelineUsers).during(Duration.ofMinutes(2)));
      default -> List.of(nothingFor(Duration.ZERO));
    };
  }

  private List<Assertion> assertions() {
    return switch (testType) {
      case "perftest", "pipeline" -> List.of(global().successfulRequests().percent().gte(95.0));
      default -> List.of();
    };
  }

  @Override
  public void before() {
    System.out.println("Test Type: " + testType);
    System.out.println("Test Environment: " + env);
    System.out.println("Debug Mode: " + debugMode);
    System.out.println("Authentication Mode: " + authMode);
    if ("sso-login".equals(authMode)) System.out.println("SSO Login Accounts: " + requiredAccountCount);
  }
}
