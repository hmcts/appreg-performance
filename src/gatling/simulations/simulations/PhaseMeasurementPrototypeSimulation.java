package simulations;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Simulation;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import scenarios.SearchScenario;
import utils.AuthenticatedSessionPool;
import utils.AuthenticationStage;
import utils.DiagnosticLogging;
import utils.Environment;
import utils.Headers;
import utils.PhaseController;
import utils.PhaseController.Phase;
import utils.PrototypeSettings;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.exitHereIfFailed;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.Cookie;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.addCookie;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.headerRegex;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.AppRegHttp.protocol;
import static utils.Headers.COMMON_HEADER;

/**
 * Read-only proof that a small authenticated session pool can support a larger number of
 * concurrent workload actors. Shared-session writes may race, so keep this prototype read-only
 * until the mixed action set has separate evidence.
 */
public class PhaseMeasurementPrototypeSimulation extends Simulation {
  private static final int SEARCH_P95_LIMIT_MILLIS = 5_000;
  private static final String SEARCH_GROUP = "AppReg_030_Application_List_Search";
  private static final String RAMP_UP_GROUP = "Prototype_Ramp_Up_Application_List_Search";
  private static final String SEARCH_DESCRIPTION = System.getProperty(
      "appRegApplicationListSearchDescription", "Bulk");
  private static final String ACTION_PHASE_SESSION_KEY = "prototypeActionPhase";
  private static final String ACTOR_INDEX_SESSION_KEY = "prototypeActorIndex";
  private static final String CAPTURED_SESSION_COOKIE_KEY = "prototypeCapturedSessionCookie";
  private static final String CAPTURED_XSRF_TOKEN_KEY = "prototypeCapturedXsrfToken";
  private static final Duration POOL_WAIT_POLL_INTERVAL = Duration.ofMillis(100);
  private static final URI APPREG_ORIGIN = URI.create(Environment.BASE_URL);

  private final PrototypeSettings settings = PrototypeSettings.fromRuntime();
  private final int actors = settings.users();
  private final AuthenticatedSessionPool sessionPool = new AuthenticatedSessionPool(settings.sessionPoolSize());
  private final PhaseController phases = new PhaseController(
      actors,
      settings.authenticationSetupTimeout(),
      settings.steadyStateDuration(),
      Duration.ofSeconds(settings.rampDownGraceSeconds()));
  private final AtomicInteger nextActorIndex = new AtomicInteger();
  private final AtomicBoolean poolReadyLogged = new AtomicBoolean();
  private final AtomicBoolean measuredPhaseLogged = new AtomicBoolean();
  private final AtomicBoolean rampDownPhaseLogged = new AtomicBoolean();
  private final AtomicBoolean setupFailureLogged = new AtomicBoolean();
  private final AtomicBoolean completionFailureLogged = new AtomicBoolean();

  public PhaseMeasurementPrototypeSimulation() {
    var poolAccounts = SsoAuthentication.users(settings.sessionPoolSize());
    var authenticators = scenario("AppReg prototype session-pool authentication")
      .exitBlockOnFail().on(
        feed(poolAccounts)
          .exec(AuthenticationStage.authenticate())
          .exec(getCookieValue(CookieKey(Headers.APPREG_SESSION_COOKIE)
            .saveAs(CAPTURED_SESSION_COOKIE_KEY)))
          .exec(getCookieValue(CookieKey(Headers.XSRF_TOKEN_COOKIE)
            .saveAs(CAPTURED_XSRF_TOKEN_KEY)))
          .exec(session -> {
            sessionPool.add(
                session.getString(CAPTURED_SESSION_COOKIE_KEY),
                session.getString(CAPTURED_XSRF_TOKEN_KEY));
            if (sessionPool.ready()) {
              logPhaseOnce(
                  poolReadyLogged,
                  "SESSION POOL READY",
                  settings.sessionPoolSize() + " authenticated sessions; releasing "
                      + actors + " concurrent-access actors");
            }
            return session
                .remove(CAPTURED_SESSION_COOKIE_KEY)
                .remove(CAPTURED_XSRF_TOKEN_KEY);
          }));

    var prototypeActors = scenario("AppReg pooled-session concurrent-access prototype")
      .exec(session -> session.set(ACTOR_INDEX_SESSION_KEY, nextActorIndex.getAndIncrement()))
      .asLongAs(session -> !sessionPool.ready()
          && phases.currentPhase() == Phase.AUTHENTICATION_RAMP_UP).on(
            pause(POOL_WAIT_POLL_INTERVAL))
      .exec(session -> sessionPool.ready() ? session : session.markAsFailed())
      .exec(exitHereIfFailed())
      .exec(addCookie(Cookie(
          Headers.APPREG_SESSION_COOKIE,
          session -> sessionPool.sessionForActor(
            session.getInt(ACTOR_INDEX_SESSION_KEY)).sessionCookieValue())
        .withDomain(APPREG_ORIGIN.getHost())
        .withPath("/")
        .withSecure("https".equalsIgnoreCase(APPREG_ORIGIN.getScheme()))))
      .exec(addCookie(Cookie(
          Headers.XSRF_TOKEN_COOKIE,
          session -> sessionPool.sessionForActor(
            session.getInt(ACTOR_INDEX_SESSION_KEY)).xsrfTokenValue())
        .withDomain(APPREG_ORIGIN.getHost())
        .withPath("/")
        .withSecure("https".equalsIgnoreCase(APPREG_ORIGIN.getScheme()))))
      .exec(http("Prototype pooled AppReg session check")
        .get("/sso/me")
        .transformResponse(DiagnosticLogging.logIfStatusAtLeast(
          "Prototype pooled AppReg session check", 400))
        .header("Accept", "application/json")
        .check(status().is(200))
        .check(jsonPath("$.authenticated").is("true")))
      .exec(exitHereIfFailed())
      .exec(session -> {
        var phase = phases.registerAuthenticatedSession();
        if (phase == Phase.MEASURED_STEADY_STATE) {
          logPhaseOnce(
              measuredPhaseLogged,
              "MEASURED STEADY STATE",
              actors + " concurrent-access actors using " + settings.sessionPoolSize()
                  + " authenticated sessions for " + minutes(settings.steadyStateMinutes()));
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
            sessionPool.size() + " of " + settings.sessionPoolSize()
                + " sessions authenticated and " + phases.authenticatedUsers() + " of " + actors
                + " actors validated before the "
                + minutes(settings.authenticationSetupTimeoutMinutes()) + " deadline");
        return session.markAsFailed();
      });

    setUp(
        authenticators.injectOpen(
          rampUsers(settings.sessionPoolSize()).during(settings.authenticationRampDuration())),
        prototypeActors.injectOpen(atOnceUsers(actors)))
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
        "SESSION POOL AUTHENTICATION",
        settings.sessionPoolSize() + " SSO journeys at "
            + format(settings.authenticationRatePerSecond())
            + " sessions/second; " + actors + " workload actors waiting");
  }

  @Override
  public void after() {
    var finalPhase = phases.currentPhase();
    if (finalPhase == Phase.RAMP_DOWN
        && sessionPool.size() == settings.sessionPoolSize()
        && phases.targetReached()
        && phases.completedUsers() == actors
        && phases.lateCompletions() == 0) {
      logPhase(
          "EXECUTION COMPLETE",
          sessionPool.size() + " authenticated sessions supported " + actors
              + " completed concurrent-access actors; Gatling assertions determine pass or fail");
      return;
    }
    logPhase(
        "INCOMPLETE",
        sessionPool.size() + " of " + settings.sessionPoolSize() + " sessions authenticated; "
            + phases.authenticatedUsers() + " of " + actors + " actors validated; "
            + phases.completedUsers() + " completed; " + phases.lateCompletions()
            + " exceeded the completion grace; final phase " + finalPhase);
    throw new IllegalStateException("Pooled-session prototype did not complete its measured steady state");
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
    System.out.println("Concurrent-access actors: " + actors);
    System.out.println("Authenticated session pool: " + settings.sessionPoolSize());
    System.out.println("SSO journeys: " + settings.sessionPoolSize());
    System.out.println("Actors per authenticated session: "
        + format((double) actors / settings.sessionPoolSize()));
    System.out.println("Session authentication rate: "
        + format(settings.authenticationRatePerSecond()) + " sessions/second");
    System.out.println("Authentication setup deadline: "
        + minutes(settings.authenticationSetupTimeoutMinutes()));
    System.out.println("Measured steady state: " + minutes(settings.steadyStateMinutes()));
    System.out.println("Action pace: " + seconds(settings.actionPaceSeconds()));
    System.out.println("Ramp-down grace: " + seconds(settings.rampDownGraceSeconds()));
    System.out.println("Evidence boundary: concurrent access, not distinct users or sessions");
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
