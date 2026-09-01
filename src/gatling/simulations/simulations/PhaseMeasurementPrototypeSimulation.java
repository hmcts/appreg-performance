package simulations;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import static io.gatling.javaapi.core.CoreDsl.responseTimeInMillis;
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
import static utils.PrototypeGatewayRetryPolicy.isTransient;
import static utils.PrototypeGatewayRetryPolicy.percentile95;
import static utils.PrototypeGatewayRetryPolicy.shouldRetry;

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
  private static final String GATEWAY_ATTEMPT_KEY = "prototypeGatewayAttempt";
  private static final String GATEWAY_RETRY_PENDING_KEY = "prototypeGatewayRetryPending";
  private static final String GATEWAY_STATUS_KEY = "prototypeGatewayStatus";
  private static final String GATEWAY_RESPONSE_MILLIS_KEY = "prototypeGatewayResponseMillis";
  private static final String LOGICAL_ACTION_MILLIS_KEY = "prototypeLogicalActionMillis";
  private static final String LOGICAL_ACTION_RECOVERED_KEY = "prototypeLogicalActionRecovered";
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
  private final AtomicInteger transientGatewayFailures = new AtomicInteger();
  private final AtomicInteger recoveredGatewayOperations = new AtomicInteger();
  private final AtomicInteger recoveredLogicalActions = new AtomicInteger();
  private final AtomicInteger exhaustedGatewayFailures = new AtomicInteger();
  private final ConcurrentLinkedQueue<Long> logicalActionDurationsMillis =
      new ConcurrentLinkedQueue<>();

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
      .exec(gatewayAwareSessionCheck())
      .exec(exitHereIfFailed())
      .pause(session -> settings.actionSpreadForActor(session.getInt(ACTOR_INDEX_SESSION_KEY)))
      .exec(session -> {
        var phase = phases.registerReadyActor();
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
                gatewayAwareMeasuredSearch()))
      .exec(session -> {
        var phase = phases.currentPhase();
        if (phase == Phase.RAMP_DOWN) {
          logPhaseOnce(
              rampDownPhaseLogged,
              "RAMP-DOWN",
              "measured window closed; no new actions will start");
          if (phases.actorCompleted()) return session;
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
                + " sessions authenticated and " + phases.readyActors() + " of " + actors
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
        details(SEARCH_GROUP).successfulRequests().percent().gte(100.0));
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
    var executionComplete = finalPhase == Phase.RAMP_DOWN
        && sessionPool.size() == settings.sessionPoolSize()
        && phases.targetReached()
        && phases.completedActors() == actors
        && phases.lateCompletions() == 0;
    var logicalResultsPass = logLogicalResults();
    if (executionComplete && logicalResultsPass) {
      logPhase(
          "EXECUTION COMPLETE",
          sessionPool.size() + " authenticated sessions supported " + actors
              + " completed concurrent-access actors; logical and Gatling assertions passed");
      return;
    }
    logPhase(
        "INCOMPLETE",
        sessionPool.size() + " of " + settings.sessionPoolSize() + " sessions authenticated; "
            + phases.readyActors() + " of " + actors + " actors validated; "
            + phases.completedActors() + " completed; " + phases.lateCompletions()
            + " exceeded the completion grace; final phase " + finalPhase);
    if (!logicalResultsPass) {
      throw new IllegalStateException("Prototype logical NFR result failed");
    }
    throw new IllegalStateException("Pooled-session prototype did not complete its measured steady state");
  }

  private ChainBuilder gatewayAwareMeasuredSearch() {
    return group(SEARCH_GROUP).on(
      exec(session -> session
        .set(LOGICAL_ACTION_MILLIS_KEY, 0L)
        .set(LOGICAL_ACTION_RECOVERED_KEY, false))
        .exec(gatewayAwareApplicationsPage())
        .exec(exitHereIfFailed())
        .exec(gatewayAwareApplicationSearch())
        .exec(exitHereIfFailed())
        .exec(session -> {
          logicalActionDurationsMillis.add(session.getLong(LOGICAL_ACTION_MILLIS_KEY));
          if (session.getBoolean(LOGICAL_ACTION_RECOVERED_KEY)) {
            recoveredLogicalActions.incrementAndGet();
          }
          return session
              .remove(LOGICAL_ACTION_MILLIS_KEY)
              .remove(LOGICAL_ACTION_RECOVERED_KEY);
        }));
  }

  private ChainBuilder gatewayAwareApplicationsPage() {
    var attempt = exec(http("Prototype support attempt: Applications list page")
      .get(Environment.APPLICATIONS_LIST_PATH)
      .headers(COMMON_HEADER)
      .transformResponse(DiagnosticLogging.logIfStatusAtLeast(
        "Prototype applications list page", 400))
      .check(status().saveAs(GATEWAY_STATUS_KEY))
      .check(responseTimeInMillis().saveAs(GATEWAY_RESPONSE_MILLIS_KEY))
      .checkIf((response, session) -> !isTransient(response.status().code())).then(
        status().is(200)));
    return gatewayRetry("Measured applications list page", attempt, true);
  }

  private ChainBuilder gatewayAwareApplicationSearch() {
    var attempt = exec(http("Prototype support attempt: Search application lists by description")
      .get("/application-lists")
      .transformResponse(DiagnosticLogging.logIfStatusAtLeast(
        "Prototype search application lists by description", 400))
      .queryParam("description", SEARCH_DESCRIPTION)
      .queryParam("pageNumber", 0)
      .queryParam("pageSize", 10)
      .queryParam("sort", "date,desc")
      .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
      .check(status().saveAs(GATEWAY_STATUS_KEY))
      .check(responseTimeInMillis().saveAs(GATEWAY_RESPONSE_MILLIS_KEY))
      .checkIf((response, session) -> !isTransient(response.status().code())).then(
        status().is(200))
      .checkIf((response, session) -> response.status().code() == 200).then(
        headerRegex("Content-Type", ".*json.*"),
        jsonPath("$.content[0].id").optional().saveAs("applicationListId")));
    return gatewayRetry("Measured application-list search", attempt, true);
  }

  private ChainBuilder gatewayAwareSessionCheck() {
    var attempt = exec(http("Prototype support attempt: Pooled AppReg session check")
      .get("/sso/me")
      .transformResponse(DiagnosticLogging.logIfStatusAtLeast(
        "Prototype pooled AppReg session check", 400))
      .header("Accept", "application/json")
      .check(status().saveAs(GATEWAY_STATUS_KEY))
      .check(responseTimeInMillis().saveAs(GATEWAY_RESPONSE_MILLIS_KEY))
      .checkIf((response, session) -> !isTransient(response.status().code())).then(
        status().is(200))
      .checkIf((response, session) -> response.status().code() == 200).then(
        jsonPath("$.authenticated").is("true")));
    return gatewayRetry("Pooled AppReg session check", attempt, false);
  }

  private ChainBuilder gatewayRetry(
      String operation, ChainBuilder attempt, boolean measuredOperation) {
    return exec(session -> session
      .set(GATEWAY_ATTEMPT_KEY, 0)
      .set(GATEWAY_RETRY_PENDING_KEY, true))
      .doWhile(session -> session.getBoolean(GATEWAY_RETRY_PENDING_KEY)).on(
        exec(attempt)
          .exec(session -> evaluateGatewayAttempt(operation, measuredOperation, session))
          .doIf(session -> session.getBoolean(GATEWAY_RETRY_PENDING_KEY)).then(
            pause(settings.gatewayRetryDelay())))
      .exec(session -> session
        .remove(GATEWAY_ATTEMPT_KEY)
        .remove(GATEWAY_RETRY_PENDING_KEY)
        .remove(GATEWAY_STATUS_KEY)
        .remove(GATEWAY_RESPONSE_MILLIS_KEY));
  }

  private Session evaluateGatewayAttempt(
      String operation, boolean measuredOperation, Session session) {
    if (session.isFailed()) {
      return session.set(GATEWAY_RETRY_PENDING_KEY, false);
    }

    int attempt = session.getInt(GATEWAY_ATTEMPT_KEY) + 1;
    int statusCode = session.getInt(GATEWAY_STATUS_KEY);
    int responseMillis = session.getInt(GATEWAY_RESPONSE_MILLIS_KEY);
    if (statusCode == 200) {
      if (attempt > 1) {
        recoveredGatewayOperations.incrementAndGet();
        System.out.printf(
            "APPREG_GATEWAY_RECOVERED timestamp=%s operation=%s attempt=%d status=200 successfulResponseMillis=%d%n",
            Instant.now(), operation, attempt, responseMillis);
      }
      var successful = session
          .set(GATEWAY_ATTEMPT_KEY, attempt)
          .set(GATEWAY_RETRY_PENDING_KEY, false);
      if (!measuredOperation) return successful;
      return successful.set(
          LOGICAL_ACTION_MILLIS_KEY,
          session.getLong(LOGICAL_ACTION_MILLIS_KEY) + responseMillis);
    }

    if (!isTransient(statusCode)) {
      return session.set(GATEWAY_RETRY_PENDING_KEY, false).markAsFailed();
    }

    transientGatewayFailures.incrementAndGet();
    boolean willRetry = shouldRetry(statusCode, attempt, settings.gatewayRetries());
    System.out.printf(
        "APPREG_GATEWAY_RETRY timestamp=%s operation=%s status=%d attempt=%d maxAttempts=%d retry=%s delaySeconds=%s%n",
        Instant.now(),
        operation,
        statusCode,
        attempt,
        settings.gatewayRetries() + 1,
        willRetry,
        willRetry ? format(settings.gatewayRetryDelaySeconds()) : "-");
    var updated = session
        .set(GATEWAY_ATTEMPT_KEY, attempt)
        .set(GATEWAY_RETRY_PENDING_KEY, willRetry);
    if (measuredOperation) {
      updated = updated.set(LOGICAL_ACTION_RECOVERED_KEY, true);
    }
    if (willRetry) return updated;

    exhaustedGatewayFailures.incrementAndGet();
    return updated.markAsFailed();
  }

  private boolean logLogicalResults() {
    var p95Millis = percentile95(logicalActionDurationsMillis);
    var passed = !logicalActionDurationsMillis.isEmpty()
        && exhaustedGatewayFailures.get() == 0
        && p95Millis < SEARCH_P95_LIMIT_MILLIS;
    System.out.println("========== PROTOTYPE LOGICAL RESULTS ==========");
    System.out.println("Completed logical actions: " + logicalActionDurationsMillis.size());
    System.out.println("Transient gateway failures observed: " + transientGatewayFailures.get());
    System.out.println("Recovered gateway operations: " + recoveredGatewayOperations.get());
    System.out.println("Recovered logical actions: " + recoveredLogicalActions.get());
    System.out.println("Exhausted gateway failures: " + exhaustedGatewayFailures.get());
    System.out.println("Logical action p95 excluding transient attempts: " + p95Millis + " ms");
    System.out.println("Logical NFR limit: < " + SEARCH_P95_LIMIT_MILLIS + " ms");
    System.out.println("Logical NFR verdict: " + (passed ? "PASS" : "FAIL"));
    System.out.println("================================================");
    return passed;
  }

  private ChainBuilder rampUpSearch() {
    return group(RAMP_UP_GROUP).on(
      exec(gatewayAwareRampUpApplicationsPage())
        .exec(exitHereIfFailed())
        .exec(gatewayAwareRampUpApplicationSearch())
        .exec(exitHereIfFailed())
    );
  }

  private ChainBuilder gatewayAwareRampUpApplicationsPage() {
    var attempt = exec(http("Prototype support attempt: Ramp-up applications list page")
      .get(Environment.APPLICATIONS_LIST_PATH)
      .headers(COMMON_HEADER)
      .transformResponse(DiagnosticLogging.logIfStatusAtLeast(
        "Prototype ramp-up applications list page", 400))
      .check(status().saveAs(GATEWAY_STATUS_KEY))
      .check(responseTimeInMillis().saveAs(GATEWAY_RESPONSE_MILLIS_KEY))
      .checkIf((response, session) -> !isTransient(response.status().code())).then(
        status().is(200)));
    return gatewayRetry("Ramp-up applications list page", attempt, false);
  }

  private ChainBuilder gatewayAwareRampUpApplicationSearch() {
    var attempt = exec(http("Prototype support attempt: Ramp-up application-list search")
      .get("/application-lists")
      .transformResponse(DiagnosticLogging.logIfStatusAtLeast(
        "Prototype ramp-up application list search", 400))
      .queryParam("description", SEARCH_DESCRIPTION)
      .queryParam("pageNumber", 0)
      .queryParam("pageSize", 10)
      .queryParam("sort", "date,desc")
      .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
      .check(status().saveAs(GATEWAY_STATUS_KEY))
      .check(responseTimeInMillis().saveAs(GATEWAY_RESPONSE_MILLIS_KEY))
      .checkIf((response, session) -> !isTransient(response.status().code())).then(
        status().is(200))
      .checkIf((response, session) -> response.status().code() == 200).then(
        headerRegex("Content-Type", ".*json.*"),
        jsonPath("$.content[0].id").optional().saveAs("rampUpApplicationListId")));
    return gatewayRetry("Ramp-up application-list search", attempt, false);
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
    System.out.println("Initial actor action spread: " + seconds(settings.actionSpreadSeconds()));
    System.out.println("Action spread policy: stable actor-index offsets; 0 seconds means intentional burst");
    System.out.println("Ramp-down grace: " + seconds(settings.rampDownGraceSeconds()));
    System.out.println("Gateway retry policy: " + settings.gatewayRetries()
        + " retries after HTTP 502/504; delay " + seconds(settings.gatewayRetryDelaySeconds()));
    System.out.println("Logical timing: successful operation responses only; retry attempts and delay excluded");
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
