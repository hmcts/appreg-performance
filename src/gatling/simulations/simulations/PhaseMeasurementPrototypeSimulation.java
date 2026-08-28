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
import utils.PrototypeSettings;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.during;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.exitHereIfFailed;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.rendezVous;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.headerRegex;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.AppRegHttp.protocol;
import static utils.Headers.COMMON_HEADER;

/**
 * Read-only proof that authenticated sessions can cross explicit warm-up and measured phase
 * boundaries without logging in again.
 */
public class PhaseMeasurementPrototypeSimulation extends Simulation {
  private static final int SEARCH_P95_LIMIT_MILLIS = 5_000;
  private static final String SEARCH_GROUP = "AppReg_030_Application_List_Search";
  private static final String WARM_UP_GROUP = "Prototype_Warm_Up_Application_List_Search";

  private final PrototypeSettings settings = PrototypeSettings.fromRuntime();
  private final int users = settings.users();
  private final int steadyStateMinutes = settings.steadyStateMinutes();
  private final AtomicBoolean warmUpPhaseLogged = new AtomicBoolean();
  private final AtomicBoolean measuredPhaseLogged = new AtomicBoolean();
  private final AtomicBoolean rampDownPhaseLogged = new AtomicBoolean();

  public PhaseMeasurementPrototypeSimulation() {
    var prototypeUsers = SsoAuthentication.users(users);
    var prototype = scenario("AppReg phase measurement prototype")
      .exitBlockOnFail().on(
        feed(prototypeUsers)
          .exec(AuthenticationStage.authenticate(users)))
      .exec(exitHereIfFailed())
      .exec(rendezVous(users))
      .exec(logPhaseOnce(warmUpPhaseLogged, "WARM-UP", users + " authenticated sessions"))
      .exec(warmUpSearch())
      .exec(rendezVous(users))
      .exec(logPhaseOnce(
        measuredPhaseLogged,
        "MEASURED STEADY STATE",
        users + " active sessions for " + steadyStateMinutes + " minutes"))
      .exec(during(Duration.ofMinutes(steadyStateMinutes), true).on(
        exec(pace(Duration.ofMinutes(1)))
          .exec(SearchScenario.searchApplicationLists())))
      .exec(rendezVous(users))
      .exec(logPhaseOnce(rampDownPhaseLogged, "RAMP-DOWN", "all measured actions completed"));

    setUp(prototype.injectOpen(rampUsers(users).during(Duration.ofSeconds(users))))
      .protocols(protocol())
      .maxDuration(Duration.ofSeconds(users).plusMinutes(steadyStateMinutes + 5L))
      .assertions(
        global().successfulRequests().percent().gte(100.0),
        details(SEARCH_GROUP).successfulRequests().percent().gte(100.0),
        details(SEARCH_GROUP).responseTime().percentile(95.0).lt(SEARCH_P95_LIMIT_MILLIS));
  }

  @Override
  public void before() {
    logPhase("AUTHENTICATION AND RAMP-UP", users + " users at one user per second");
  }

  @Override
  public void after() {
    if (rampDownPhaseLogged.get()) {
      logPhase("EXECUTION COMPLETE", "phase flow completed; Gatling assertions determine pass or fail");
    } else {
      logPhase("INCOMPLETE", "prototype stopped before all sessions reached ramp-down");
    }
  }

  private static ChainBuilder warmUpSearch() {
    return group(WARM_UP_GROUP).on(
      exec(http("Prototype warm-up applications list page")
        .get(Environment.APPLICATIONS_LIST_PATH)
        .headers(COMMON_HEADER)
        .check(status().is(200)))
        .exec(http("Prototype warm-up application list search")
          .get("/application-lists")
          .transformResponse(DiagnosticLogging.logIfStatusAtLeast(
            "Prototype warm-up application list search", 400))
          .queryParam("description", "Bulk")
          .queryParam("pageNumber", 0)
          .queryParam("pageSize", 10)
          .queryParam("sort", "date,desc")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
          .check(status().is(200))
          .check(headerRegex("Content-Type", ".*json.*"))
          .check(jsonPath("$.content[0].id").optional().saveAs("warmUpApplicationListId")))
    );
  }

  private static ChainBuilder logPhaseOnce(AtomicBoolean marker, String phase, String detail) {
    return exec(session -> {
      if (marker.compareAndSet(false, true)) logPhase(phase, detail);
      return session;
    });
  }

  private static void logPhase(String phase, String detail) {
    System.out.println("========== PROTOTYPE PHASE: " + phase + " | " + detail + " ==========");
  }
}
