package simulations;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import scenarios.AddApplicationScenario;
import scenarios.ApplicationListCreateScenario;
import scenarios.BulkApplicationUploadScenario;
import scenarios.BulkUpdateFeesScenario;
import scenarios.BulkUpdateOfficialsScenario;
import scenarios.CloseApplicationListScenario;
import scenarios.ResultApplicationScenario;
import scenarios.ResultMultipleApplicationsScenario;
import scenarios.SearchScenario;
import scenarios.UpdateApplicationListScenario;
import scenarios.UpdateApplicationResultScenario;
import scenarios.UpdateApplicationScenario;
import utils.AuthenticationStage;
import utils.AuthenticatedSessionPool;
import utils.DiagnosticLogging;
import utils.Environment;
import utils.Headers;
import utils.PhaseController;
import utils.PhaseController.Phase;
import utils.SsoAuthentication;
import utils.WorkloadAction;
import utils.WorkloadProfile;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.doSwitch;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.exitBlockOnFail;
import static io.gatling.javaapi.core.CoreDsl.exitHereIfFailed;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.onCase;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.Cookie;
import static io.gatling.javaapi.http.HttpDsl.addCookie;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.AppRegHttp.protocol;
import static utils.Headers.XSRF_TOKEN_COOKIE;

/**
 * Phase-based, feeder-backed AppReg workload. A smaller authenticated session pool is assigned
 * round-robin to separately paced workload actors for the common measured window.
 */
public class AppRegWorkloadSimulation extends Simulation {
  private static final String RAMP_UP_GROUP = "AppReg_Ramp_Up";
  private static final String ACTION_PHASE_SESSION_KEY = "workloadActionPhase";
  private static final String ACTOR_INDEX_SESSION_KEY = "workloadActorIndex";
  private static final String CAPTURED_SESSION_COOKIE_KEY = "workloadCapturedSessionCookie";
  private static final String CAPTURED_XSRF_TOKEN_KEY = "workloadCapturedXsrfToken";
  private static final String RAMP_ITERATION_SESSION_KEY = "workloadRampIteration";
  private static final String MEASURED_ITERATION_SESSION_KEY = "workloadMeasuredIteration";
  private static final Duration POOL_WAIT_POLL_INTERVAL = Duration.ofMillis(100);
  private static final URI APPREG_ORIGIN = URI.create(Environment.BASE_URL);

  private final WorkloadProfile profile = WorkloadProfile.fromRuntime();
  private final AuthenticatedSessionPool sessionPool =
      new AuthenticatedSessionPool(profile.sessionPoolSize());
  private final PhaseController phases = new PhaseController(
      profile.concurrentUsers(),
      profile.authenticationSetupTimeout(),
      profile.steadyStateDuration(),
      Duration.ofSeconds(profile.rampDownGraceSeconds()));
  private final String feederDirectory = Path.of(System.getProperty(
      "appRegPerformanceDataDirectory", "build/workload-data")).toAbsolutePath().toString();
  private final AtomicBoolean measuredPhaseLogged = new AtomicBoolean();
  private final AtomicBoolean poolReadyLogged = new AtomicBoolean();
  private final AtomicBoolean rampDownPhaseLogged = new AtomicBoolean();
  private final AtomicBoolean setupFailureLogged = new AtomicBoolean();
  private final AtomicBoolean completionFailureLogged = new AtomicBoolean();
  private final AtomicInteger rampActionsStarted = new AtomicInteger();
  private final AtomicInteger measuredActionsStarted = new AtomicInteger();
  private final AtomicInteger nextActorIndex = new AtomicInteger();

  private final FeederBuilder.FileBased<String> rampUpdateApplicationFeeder =
      feeder("ramp-up", "update-application", WorkloadAction.UPDATE_APPLICATION);
  private final FeederBuilder.FileBased<String> rampAddApplicationFeeder =
      feeder("ramp-up", "add-application", WorkloadAction.ADD_APPLICATION);
  private final FeederBuilder.FileBased<String> rampResultApplicationFeeder =
      feeder("ramp-up", "result-application", WorkloadAction.RESULT_APPLICATION);
  private final FeederBuilder.FileBased<String> rampResultMultipleFeeder =
      feeder("ramp-up", "result-multiple", WorkloadAction.RESULT_MULTIPLE);
  private final FeederBuilder.FileBased<String> rampUpdateResultFeeder =
      feeder("ramp-up", "update-result", WorkloadAction.UPDATE_RESULT);
  private final FeederBuilder.FileBased<String> rampUpdateListFeeder =
      feeder("ramp-up", "update-list", WorkloadAction.UPDATE_LIST);
  private final FeederBuilder.FileBased<String> rampCloseListFeeder =
      feeder("ramp-up", "close-list", WorkloadAction.CLOSE_LIST);
  private final FeederBuilder.FileBased<String> rampBulkOfficialsFeeder =
      feeder("ramp-up", "bulk-officials", WorkloadAction.BULK_OFFICIALS);
  private final FeederBuilder.FileBased<String> rampBulkFeesFeeder =
      feeder("ramp-up", "bulk-fees", WorkloadAction.BULK_FEES);
  private final FeederBuilder.FileBased<String> rampBulkUploadFeeder =
      feeder("ramp-up", "bulk-upload", WorkloadAction.BULK_UPLOAD);

  private final FeederBuilder.FileBased<String> measuredUpdateApplicationFeeder =
      feeder("measured", "update-application", WorkloadAction.UPDATE_APPLICATION);
  private final FeederBuilder.FileBased<String> measuredAddApplicationFeeder =
      feeder("measured", "add-application", WorkloadAction.ADD_APPLICATION);
  private final FeederBuilder.FileBased<String> measuredResultApplicationFeeder =
      feeder("measured", "result-application", WorkloadAction.RESULT_APPLICATION);
  private final FeederBuilder.FileBased<String> measuredResultMultipleFeeder =
      feeder("measured", "result-multiple", WorkloadAction.RESULT_MULTIPLE);
  private final FeederBuilder.FileBased<String> measuredUpdateResultFeeder =
      feeder("measured", "update-result", WorkloadAction.UPDATE_RESULT);
  private final FeederBuilder.FileBased<String> measuredUpdateListFeeder =
      feeder("measured", "update-list", WorkloadAction.UPDATE_LIST);
  private final FeederBuilder.FileBased<String> measuredCloseListFeeder =
      feeder("measured", "close-list", WorkloadAction.CLOSE_LIST);
  private final FeederBuilder.FileBased<String> measuredBulkOfficialsFeeder =
      feeder("measured", "bulk-officials", WorkloadAction.BULK_OFFICIALS);
  private final FeederBuilder.FileBased<String> measuredBulkFeesFeeder =
      feeder("measured", "bulk-fees", WorkloadAction.BULK_FEES);
  private final FeederBuilder.FileBased<String> measuredBulkUploadFeeder =
      feeder("measured", "bulk-upload", WorkloadAction.BULK_UPLOAD);

  public AppRegWorkloadSimulation() {
    var poolAccounts = SsoAuthentication.users(profile.sessionPoolSize());
    var authenticators = scenario("AppReg workload session-pool authentication")
      .exitBlockOnFail().on(
        feed(poolAccounts)
          .exec(AuthenticationStage.authenticate())
          .exec(getCookieValue(CookieKey(Headers.APPREG_SESSION_COOKIE)
            .saveAs(CAPTURED_SESSION_COOKIE_KEY)))
          .exec(getCookieValue(CookieKey(XSRF_TOKEN_COOKIE)
            .saveAs(CAPTURED_XSRF_TOKEN_KEY)))
          .exec(session -> {
            sessionPool.add(
                session.getString(CAPTURED_SESSION_COOKIE_KEY),
                session.getString(CAPTURED_XSRF_TOKEN_KEY));
            if (sessionPool.ready()) {
              logPhaseOnce(
                  poolReadyLogged,
                  "SESSION POOL READY",
                  profile.sessionPoolSize() + " authenticated sessions; assigning "
                      + profile.concurrentUsers() + " workload actors");
            }
            return session
                .remove(CAPTURED_SESSION_COOKIE_KEY)
                .remove(CAPTURED_XSRF_TOKEN_KEY);
          }));

    var workload = scenario("AppReg pooled-session phase-based workload")
      .exec(session -> {
        int actorIndex = nextActorIndex.getAndIncrement();
        return session
            .set(ACTOR_INDEX_SESSION_KEY, actorIndex)
            // Existing deterministic plans and diagnostics use accountOffset; it now identifies
            // the workload actor rather than an SSO account.
            .set("accountOffset", actorIndex);
      })
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
          XSRF_TOKEN_COOKIE,
          session -> sessionPool.sessionForActor(
            session.getInt(ACTOR_INDEX_SESSION_KEY)).xsrfTokenValue())
        .withDomain(APPREG_ORIGIN.getHost())
        .withPath("/")
        .withSecure("https".equalsIgnoreCase(APPREG_ORIGIN.getScheme()))))
      .exec(getCookieValue(CookieKey(XSRF_TOKEN_COOKIE).saveAs("xsrfToken")))
      .exec(pooledSessionCheck())
      .exec(exitHereIfFailed())
      .pause(session -> profile.actionSpreadForActor(session.getInt(ACTOR_INDEX_SESSION_KEY)))
      .exec(session -> registerReadyActor(session
        .set(RAMP_ITERATION_SESSION_KEY, 0)
        .set(MEASURED_ITERATION_SESSION_KEY, 0)))
      .asLongAs(session -> acceptsActions(phases.currentPhase())).on(
        exec(pace(profile.actionPace()))
          // Capture the phase after pacing, immediately before choosing the action. The stored
          // value deliberately keeps an in-flight action in the phase in which it started.
          .exec(this::assignNextAction)
          .doIf(session -> Phase.AUTHENTICATION_RAMP_UP.name().equals(
              session.getString(ACTION_PHASE_SESSION_KEY))).then(
                group(RAMP_UP_GROUP).on(workloadAction(true)))
          .doIf(session -> Phase.MEASURED_STEADY_STATE.name().equals(
              session.getString(ACTION_PHASE_SESSION_KEY))).then(workloadAction(false))
          .exec(exitHereIfFailed()))
      .exec(this::completeSession);

    // One finite actor population is intentional: replacing completed actors would require
    // mutable-data reuse. The measured window, rather than injection, defines concurrency.
    setUp(
        authenticators.injectOpen(
          rampUsers(profile.sessionPoolSize()).during(profile.authenticationRampUpDuration())),
        workload.injectOpen(atOnceUsers(profile.concurrentUsers())))
      .protocols(protocol())
      .maxDuration(profile.maximumSimulationDuration())
      .assertions(global().successfulRequests().percent().gte(100.0));
  }

  @Override
  public void before() {
    phases.start();
    logConfiguration();
    logPhase(
        "SESSION POOL AUTHENTICATION",
        profile.sessionPoolSize() + " SSO journeys over "
            + seconds(profile.authenticationRampUpSeconds()) + "; "
            + profile.concurrentUsers() + " workload actors waiting");
  }

  @Override
  public void after() {
    Phase finalPhase = phases.currentPhase();
    if (finalPhase == Phase.RAMP_DOWN
        && phases.targetReached()
        && sessionPool.size() == profile.sessionPoolSize()
        && phases.completedActors() == profile.concurrentUsers()
        && phases.lateCompletions() == 0) {
      logPhase(
          "EXECUTION COMPLETE",
          sessionPool.size() + " authenticated sessions supported " + phases.completedActors()
              + " completed actors; " + rampActionsStarted.get() + " ramp-up and "
              + measuredActionsStarted.get() + " measured actions started");
      return;
    }
    logPhase(
        "INCOMPLETE",
        sessionPool.size() + " of " + profile.sessionPoolSize()
            + " sessions authenticated; " + phases.readyActors() + " of "
            + profile.concurrentUsers() + " actors validated; " + phases.completedActors()
            + " completed; "
            + phases.lateCompletions() + " exceeded the completion grace; final phase "
            + finalPhase);
    throw new IllegalStateException("Workload did not complete its measured steady state");
  }

  private Session registerReadyActor(Session session) {
    Phase phase = phases.registerReadyActor();
    if (phase == Phase.MEASURED_STEADY_STATE) {
      logPhaseOnce(
          measuredPhaseLogged,
          "MEASURED STEADY STATE",
          profile.concurrentUsers() + " active actors using " + profile.sessionPoolSize()
              + " authenticated sessions for "
              + minutes(profile.durationMinutes()));
    }
    return session;
  }

  private Session assignNextAction(Session session) {
    Phase phase = phases.currentPhase();
    session = session.set(ACTION_PHASE_SESSION_KEY, phase.name());
    // Each phase has its own iteration counter and action plan. Ramp-up work can therefore never
    // advance the measured plan or consume data reserved for a measured transaction.
    if (phase == Phase.AUTHENTICATION_RAMP_UP) {
      int iteration = session.getInt(RAMP_ITERATION_SESSION_KEY);
      WorkloadAction action = profile.rampActionFor(
          session.getInt(ACTOR_INDEX_SESSION_KEY), iteration);
      rampActionsStarted.incrementAndGet();
      return session
          .set("plannedAction", action.key())
          .set(RAMP_ITERATION_SESSION_KEY, iteration + 1);
    }
    if (phase == Phase.MEASURED_STEADY_STATE) {
      int iteration = session.getInt(MEASURED_ITERATION_SESSION_KEY);
      WorkloadAction action = profile.actionFor(
          session.getInt(ACTOR_INDEX_SESSION_KEY), iteration);
      measuredActionsStarted.incrementAndGet();
      return session
          .set("plannedAction", action.key())
          .set(MEASURED_ITERATION_SESSION_KEY, iteration + 1);
    }
    return session;
  }

  private Session completeSession(Session session) {
    Phase phase = phases.currentPhase();
    if (phase == Phase.RAMP_DOWN) {
      logPhaseOnce(
          rampDownPhaseLogged,
          "RAMP-DOWN",
          "measured window closed; no new actions will start");
      if (phases.actorCompleted()) return session;
      logPhaseOnce(
          completionFailureLogged,
          "RAMP-DOWN FAILED",
          "an in-flight action exceeded the " + seconds(profile.rampDownGraceSeconds())
              + " completion grace");
      return session.markAsFailed();
    }
    logPhaseOnce(
        setupFailureLogged,
        "SETUP FAILED",
        sessionPool.size() + " of " + profile.sessionPoolSize()
            + " sessions authenticated and " + phases.readyActors() + " of "
            + profile.concurrentUsers() + " actors validated before the "
            + minutes(profile.authenticationSetupTimeoutMinutes()) + " deadline");
    return session.markAsFailed();
  }

  private FeederBuilder.FileBased<String> feeder(
      String phase, String action, WorkloadAction scheduledAction) {
    String path = Path.of(feederDirectory, phase, action + ".csv").toString();
    // Queue semantics make every mutable row single-use. Requiring the exact planned size at
    // startup turns missing or accidentally shared data into an immediate configuration failure.
    FeederBuilder.FileBased<String> feeder = csv(path).queue();
    int requiredRows = "ramp-up".equals(phase)
        ? profile.rampScheduledActionCount(scheduledAction)
        : profile.scheduledActionCount(scheduledAction);
    if (feeder.recordsCount() != requiredRows) {
      throw new IllegalArgumentException(
          "Feeder " + path + " has " + feeder.recordsCount() + " rows; " + profile.name()
              + " requires exactly " + requiredRows + " for " + phase + " " + action);
    }
    return feeder;
  }

  private ChainBuilder pooledSessionCheck() {
    return exec(http("AppReg pooled workload session check")
      .get("/sso/me")
      .transformResponse(DiagnosticLogging.logIfStatusAtLeast(
        "AppReg pooled workload session check", 400))
      .header("Accept", "application/json")
      .check(status().is(200))
      .check(jsonPath("$.authenticated").is("true")));
  }

  private ChainBuilder workloadAction(boolean rampUp) {
    return doSwitch("#{plannedAction}").on(
      onCase(WorkloadAction.UPDATE_APPLICATION.key()).then(updateApplication(rampUp)),
      onCase(WorkloadAction.ADD_APPLICATION.key()).then(addApplication(rampUp)),
      onCase(WorkloadAction.RESULT_MULTIPLE.key()).then(resultMultipleApplications(rampUp)),
      onCase(WorkloadAction.UPDATE_RESULT.key()).then(updateApplicationResult(rampUp)),
      onCase(WorkloadAction.CREATE_LIST.key()).then(ApplicationListCreateScenario.createApplicationList()),
      onCase(WorkloadAction.UPDATE_LIST.key()).then(updateApplicationList(rampUp)),
      onCase(WorkloadAction.CLOSE_LIST.key()).then(closeApplicationList(rampUp)),
      onCase(WorkloadAction.RESULT_APPLICATION.key()).then(resultApplication(rampUp)),
      onCase(WorkloadAction.BULK_OFFICIALS.key()).then(bulkUpdateOfficials(rampUp)),
      onCase(WorkloadAction.BULK_FEES.key()).then(bulkUpdateFees(rampUp)),
      onCase(WorkloadAction.BULK_UPLOAD.key()).then(bulkUpload(rampUp)),
      onCase(WorkloadAction.OTHER_OPERATIONS.key()).then(SearchScenario.searchApplicationLists())
    );
  }

  private ChainBuilder updateApplication(boolean rampUp) {
    return feed(rampUp ? rampUpdateApplicationFeeder : measuredUpdateApplicationFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(UpdateApplicationScenario.updateApplication());
  }

  private ChainBuilder addApplication(boolean rampUp) {
    return feed(rampUp ? rampAddApplicationFeeder : measuredAddApplicationFeeder)
      .exec(session -> session.set("applicationListId", session.getString("application_list_id")))
      .exec(AddApplicationScenario.addApplication());
  }

  private ChainBuilder resultApplication(boolean rampUp) {
    return feed(rampUp ? rampResultApplicationFeeder : measuredResultApplicationFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(ResultApplicationScenario.resultApplication());
  }

  private ChainBuilder resultMultipleApplications(boolean rampUp) {
    return feed(rampUp ? rampResultMultipleFeeder : measuredResultMultipleFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("entryIdOne", session.getString("entry_id_one"))
        .set("entryIdTwo", session.getString("entry_id_two"))
        .set("entryIdThree", session.getString("entry_id_three")))
      .exec(ResultMultipleApplicationsScenario.resultMultipleApplications());
  }

  private ChainBuilder updateApplicationResult(boolean rampUp) {
    return feed(rampUp ? rampUpdateResultFeeder : measuredUpdateResultFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(UpdateApplicationResultScenario.updateApplicationResult());
  }

  private ChainBuilder updateApplicationList(boolean rampUp) {
    return feed(rampUp ? rampUpdateListFeeder : measuredUpdateListFeeder)
      .exec(session -> session.set("applicationListId", session.getString("application_list_id")))
      .exec(UpdateApplicationListScenario.updateApplicationList());
  }

  private ChainBuilder closeApplicationList(boolean rampUp) {
    return feed(rampUp ? rampCloseListFeeder : measuredCloseListFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(CloseApplicationListScenario.closeApplicationList());
  }

  private ChainBuilder bulkUpdateOfficials(boolean rampUp) {
    return feed(rampUp ? rampBulkOfficialsFeeder : measuredBulkOfficialsFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("entryIdOne", session.getString("entry_id_one"))
        .set("entryIdTwo", session.getString("entry_id_two"))
        .set("entryIdThree", session.getString("entry_id_three")))
      .exec(BulkUpdateOfficialsScenario.bulkUpdateOfficials());
  }

  private ChainBuilder bulkUpdateFees(boolean rampUp) {
    return feed(rampUp ? rampBulkFeesFeeder : measuredBulkFeesFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("entryIdOne", session.getString("entry_id_one"))
        .set("entryIdTwo", session.getString("entry_id_two"))
        .set("entryIdThree", session.getString("entry_id_three")))
      .exec(BulkUpdateFeesScenario.bulkUpdateFees());
  }

  private ChainBuilder bulkUpload(boolean rampUp) {
    return feed(rampUp ? rampBulkUploadFeeder : measuredBulkUploadFeeder)
      .exec(session -> session.set("applicationListId", session.getString("application_list_id")))
      .exec(BulkApplicationUploadScenario.bulkUploadApplications());
  }

  private void logConfiguration() {
    System.out.println("========== WORKLOAD CONFIGURATION ==========");
    System.out.println("Profile: " + profile.name());
    System.out.println("Concurrent-access actors: " + profile.concurrentUsers());
    System.out.println("Authenticated session pool: " + profile.sessionPoolSize());
    System.out.println("SSO journeys: " + profile.sessionPoolSize());
    System.out.println("Actors per authenticated session: "
        + format((double) profile.concurrentUsers() / profile.sessionPoolSize()));
    System.out.println("Session authentication ramp: "
        + seconds(profile.authenticationRampUpSeconds()));
    System.out.println("Authentication setup deadline: "
        + minutes(profile.authenticationSetupTimeoutMinutes()));
    System.out.println("Measured steady state: " + minutes(profile.durationMinutes()));
    System.out.println("Action pace: " + seconds(profile.actionPaceSeconds()));
    System.out.println("Initial actor action spread: " + seconds(profile.actionSpreadSeconds()));
    System.out.println("Action spread policy: stable actor-index offsets; 0 seconds means intentional burst");
    System.out.println("Ramp-down grace: " + seconds(profile.rampDownGraceSeconds()));
    System.out.println("Maximum ramp-up action capacity: " + profile.maximumRampActionCount());
    System.out.println("Ramp-up allocation totals: " + profile.rampScheduledActionCounts());
    System.out.println("Measured allocation totals: " + profile.scheduledActionCounts());
    System.out.println("Allocated feeder directory: " + feederDirectory);
    System.out.println("Evidence boundary: concurrent access, not distinct users or sessions");
    System.out.println("============================================");
  }

  private static boolean acceptsActions(Phase phase) {
    return phase == Phase.AUTHENTICATION_RAMP_UP || phase == Phase.MEASURED_STEADY_STATE;
  }

  private static void logPhaseOnce(AtomicBoolean marker, String phase, String detail) {
    if (marker.compareAndSet(false, true)) logPhase(phase, detail);
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
    System.out.println("========== WORKLOAD PHASE: " + phase + " | " + detail + " ==========");
  }
}
