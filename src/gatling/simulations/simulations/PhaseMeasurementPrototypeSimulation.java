package simulations;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Simulation;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import scenarios.SearchScenario;
import utils.AuthenticationStage;
import utils.DiagnosticLogging;
import utils.Environment;
import utils.Headers;
import utils.PhaseController;
import utils.PhaseController.Phase;
import utils.PrototypeSettings;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.exitHereIfFailed;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.headerRegex;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.AppRegHttp.protocol;
import static utils.Headers.COMMON_HEADER;

/**
 * Read-only proof that users can authenticate once, start work immediately and retain their
 * sessions when the common measured window opens.
 */
public class PhaseMeasurementPrototypeSimulation extends Simulation {
  private static final int SEARCH_P95_LIMIT_MILLIS = 5_000;
  private static final String SEARCH_GROUP = "AppReg_030_Application_List_Search";
  private static final String RAMP_UP_GROUP = "Prototype_Ramp_Up_Application_List_Search";
  private static final String SEARCH_DESCRIPTION = System.getProperty(
      "appRegApplicationListSearchDescription", "Bulk");
  private static final String ACTION_PHASE_SESSION_KEY = "prototypeActionPhase";

  private final PrototypeSettings settings = PrototypeSettings.fromRuntime();
  private final int users = settings.users();
  private final PhaseController phases = new PhaseController(
      users,
      settings.authenticationSetupTimeout(),
      settings.steadyStateDuration(),
      Duration.ofSeconds(settings.rampDownGraceSeconds()));
  private final AtomicBoolean measuredPhaseLogged = new AtomicBoolean();
  private final AtomicBoolean rampDownPhaseLogged = new AtomicBoolean();
  private final AtomicBoolean setupFailureLogged = new AtomicBoolean();
  private final AtomicBoolean completionFailureLogged = new AtomicBoolean();

  public PhaseMeasurementPrototypeSimulation() {
    var prototypeUsers = SsoAuthentication.users(users);
    var prototype = scenario("AppReg phase measurement prototype")
      .exitBlockOnFail().on(
        feed(prototypeUsers)
          .exec(AuthenticationStage.authenticate()))
      .exec(exitHereIfFailed())
      .exec(session -> {
        var phase = phases.registerAuthenticatedSession();
        if (phase == Phase.MEASURED_STEADY_STATE) {
          logPhaseOnce(
              measuredPhaseLogged,
              "MEASURED STEADY STATE",
              users + " active sessions continuing work for "
                  + minutes(settings.steadyStateMinutes()));
        }
        return session;
      })
      .asLongAs(session -> acceptsActions(phases.currentPhase())).on(
        exec(pace(settings.actionPace()))
          .exec(session -> session.set(ACTION_PHASE_SESSION_KEY, phases.currentPhase().name()))
          .doIf(session -> Phase.AUTHENTICATION_RAMP_UP.name().equals(
              session.getString(ACTION_PHASE_SESSION_KEY))).then(rampUpSearch())
          .doIf(session -> Phase.MEASURED_STEADY_STATE.name().equals(
              session.getString(ACTION_PHASE_SESSION_KEY))).then(
                SearchScenario.searchApplicationLists()))
      .exec(session -> {
        var phase = phases.currentPhase();
        if (phase == Phase.RAMP_DOWN) {
          logPhaseOnce(
              rampDownPhaseLogged,
              "RAMP-DOWN",
              "measured window closed; no new actions will start");
          if (phases.sessionCompleted()) return session;
          logPhaseOnce(
              completionFailureLogged,
              "RAMP-DOWN FAILED",
              "an in-flight action exceeded the " + seconds(settings.rampDownGraceSeconds())
                  + " completion grace");
          return session.markAsFailed();
        }
        logPhaseOnce(
            setupFailureLogged,
            "SETUP FAILED",
            phases.authenticatedUsers() + " of " + users
                + " sessions authenticated before the "
                + minutes(settings.authenticationSetupTimeoutMinutes()) + " deadline");
        return session.markAsFailed();
      });

    setUp(prototype.injectOpen(rampUsers(users).during(settings.authenticationRampDuration())))
      .protocols(protocol())
      .maxDuration(settings.maximumSimulationDuration())
      .assertions(
        global().successfulRequests().percent().gte(100.0),
        details(SEARCH_GROUP).successfulRequests().percent().gte(100.0),
        details(SEARCH_GROUP).responseTime().percentile(95.0).lt(SEARCH_P95_LIMIT_MILLIS));
  }

  @Override
  public void before() {
    phases.start();
    logConfiguration();
    logPhase(
        "AUTHENTICATION AND WORKLOAD RAMP-UP",
        users + " users at " + format(settings.authenticationRatePerSecond())
            + " users/second; each starts work after login");
  }

  @Override
  public void after() {
    var finalPhase = phases.currentPhase();
    if (finalPhase == Phase.RAMP_DOWN
        && phases.targetReached()
        && phases.completedUsers() == users
        && phases.lateCompletions() == 0) {
      logPhase(
          "EXECUTION COMPLETE",
          phases.authenticatedUsers() + " authenticated and " + phases.completedUsers()
              + " completed sessions; Gatling assertions determine pass or fail");
      return;
    }
    logPhase(
        "INCOMPLETE",
        phases.authenticatedUsers() + " of " + users + " sessions authenticated; "
            + phases.completedUsers() + " completed; " + phases.lateCompletions()
            + " exceeded the completion grace; final phase " + finalPhase);
    throw new IllegalStateException("Prototype did not complete its measured steady state");
  }

  private static ChainBuilder rampUpSearch() {
    return group(RAMP_UP_GROUP).on(
      exec(http("Prototype ramp-up applications list page")
        .get(Environment.APPLICATIONS_LIST_PATH)
        .headers(COMMON_HEADER)
        .check(status().is(200)))
        .exec(http("Prototype ramp-up application list search")
          .get("/application-lists")
          .transformResponse(DiagnosticLogging.logIfStatusAtLeast(
            "Prototype ramp-up application list search", 400))
          .queryParam("description", SEARCH_DESCRIPTION)
          .queryParam("pageNumber", 0)
          .queryParam("pageSize", 10)
          .queryParam("sort", "date,desc")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
          .check(status().is(200))
          .check(headerRegex("Content-Type", ".*json.*"))
          .check(jsonPath("$.content[0].id").optional().saveAs("rampUpApplicationListId")))
    );
  }

  private static boolean acceptsActions(Phase phase) {
    return phase == Phase.AUTHENTICATION_RAMP_UP || phase == Phase.MEASURED_STEADY_STATE;
  }

  private static void logPhaseOnce(AtomicBoolean marker, String phase, String detail) {
    if (marker.compareAndSet(false, true)) logPhase(phase, detail);
  }

  private void logConfiguration() {
    System.out.println("========== PROTOTYPE CONFIGURATION ==========");
    System.out.println("Users: " + users);
    System.out.println("Authentication rate: " + format(settings.authenticationRatePerSecond())
        + " users/second");
    System.out.println("Authentication setup deadline: "
        + minutes(settings.authenticationSetupTimeoutMinutes()));
    System.out.println("Measured steady state: " + minutes(settings.steadyStateMinutes()));
    System.out.println("Action pace: " + seconds(settings.actionPaceSeconds()));
    System.out.println("Ramp-down grace: " + seconds(settings.rampDownGraceSeconds()));
    System.out.println("=============================================");
  }

  private static String format(double value) {
    return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value);
  }

  private static String minutes(int value) {
    return value + (value == 1 ? " minute" : " minutes");
  }

  private static String seconds(double value) {
    return format(value) + (value == 1.0 ? " second" : " seconds");
  }

  private static void logPhase(String phase, String detail) {
    System.out.println("========== PROTOTYPE PHASE: " + phase + " | " + detail + " ==========");
  }
}
