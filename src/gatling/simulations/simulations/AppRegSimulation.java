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
import utils.PerformanceProfile;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.*;
import static utils.AppRegHttp.protocol;

public class AppRegSimulation extends Simulation {
  private final String testType = System.getenv().getOrDefault("TEST_TYPE", "perftest");
  private final String environment = switch (testType) {
    case "perftest", "pipeline" -> "perftest";
    default -> "**INVALID**";
  };
  private final String debugMode = System.getProperty("debug", "off");
  private final String env = System.getProperty("env", environment);
  private final String authMode = System.getProperty("authMode", System.getenv().getOrDefault("AUTH_MODE", "none"));

  private final PerformanceProfile profile = PerformanceProfile.fromRuntime();
  private final PauseType pauseOption = "off".equals(debugMode) ? constantPauses : disabledPauses;
  private final int requiredAccountCount = profile.concurrentUsers();
  private final Iterator<Map<String, Object>> ssoUserFeeder = "sso-login".equals(authMode)
    ? SsoAuthentication.users(requiredAccountCount) : List.<Map<String, Object>>of().iterator();

  public AppRegSimulation() {
    var httpProtocol = protocol();
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

  private List<OpenInjectionStep> simulationProfile() {
    return switch (testType) {
      case "perftest", "pipeline" -> "off".equals(debugMode) ? List.of(
        rampUsers(profile.concurrentUsers()).during(Duration.ofSeconds(profile.ssoRampUpSeconds()))
      ) : List.of(atOnceUsers(1));
      default -> List.of(nothingFor(Duration.ZERO));
    };
  }

  private List<Assertion> assertions() {
    return switch (testType) {
      case "perftest", "pipeline" -> List.of(global().successfulRequests().percent().gte(profile.successfulRequestsThreshold()));
      default -> List.of();
    };
  }

  @Override
  public void before() {
    System.out.println("Test Type: " + testType);
    System.out.println("Test Environment: " + env);
    System.out.println("Debug Mode: " + debugMode);
    System.out.println("Authentication Mode: " + authMode);
    System.out.println("Profile: " + profile);
    System.out.println("SSO ramp-up is calculated from the confirmed safe 10 logins-per-second rate.");
    if ("sso-login".equals(authMode)) System.out.println("SSO Login Accounts: " + requiredAccountCount);
  }
}
