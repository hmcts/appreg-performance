package simulations;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.doSwitch;
import static io.gatling.javaapi.core.CoreDsl.exec;
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
import static io.gatling.javaapi.http.HttpDsl.Cookie;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.addCookie;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;
import static utils.AppRegHttp.protocol;
import static utils.GatewayGetRetry.retryingGet;
import static utils.Headers.XSRF_TOKEN_COOKIE;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
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
import utils.AppRegTraceContext;
import utils.AuthenticatedSessionPool;
import utils.AuthenticationStage;
import utils.Environment;
import utils.GatewayGetRetry;
import utils.Headers;
import utils.PhaseController;
import utils.PhaseController.Phase;
import utils.SsoAuthentication;
import utils.WorkloadAction;
import utils.WorkloadNfrMetrics;
import utils.WorkloadProfile;

/**
 * Phase-based, feeder-backed AppReg workload. A smaller authenticated session pool is assigned
 * round-robin to separately paced workload actors for the common measured window.
 */
public class AppRegWorkloadSimulation extends Simulation {

  private static final int OPERATION_PROGRESS_BAR_WIDTH = 50;
  private static final String RAMP_UP_GROUP = "AppReg_Ramp_Up";
  private static final String ACTION_PHASE_SESSION_KEY = "workloadActionPhase";
  private static final String ACTOR_INDEX_SESSION_KEY = "workloadActorIndex";
  private static final String CAPTURED_SESSION_COOKIE_KEY = "workloadCapturedSessionCookie";
  private static final String CAPTURED_XSRF_TOKEN_KEY = "workloadCapturedXsrfToken";
  private static final String RAMP_ITERATION_SESSION_KEY = "workloadRampIteration";
  private static final String MEASURED_ITERATION_SESSION_KEY = "workloadMeasuredIteration";
  private static final Path NFR_SUMMARY_PATH =
      Path.of("build/reports/gatling/workload-nfr-summary.txt");
  private static final Duration POOL_WAIT_POLL_INTERVAL = Duration.ofMillis(100);
  private static final URI APPREG_ORIGIN = URI.create(Environment.BASE_URL);
  public static final String RAMP_UP = "ramp-up";
  public static final String MEASURED = "measured";
  public static final String PLANNED_ACTION = "plannedAction";

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
  private final AtomicInteger measuredActionsFinished = new AtomicInteger();
  private final AtomicInteger lastLoggedOperationPercentage = new AtomicInteger(-1);
  private long workloadStartedNanos;
  private final AtomicInteger nextActorIndex = new AtomicInteger();
  private final WorkloadNfrMetrics nfrMetrics = new WorkloadNfrMetrics();

  private final FeederBuilder.FileBased<String> rampUpdateApplicationFeeder =
      feeder(RAMP_UP, "update-application", WorkloadAction.UPDATE_APPLICATION);
  private final FeederBuilder.FileBased<String> rampAddApplicationFeeder =
      feeder(RAMP_UP, "add-application", WorkloadAction.ADD_APPLICATION);
  private final FeederBuilder.FileBased<String> rampResultApplicationFeeder =
      feeder(RAMP_UP, "result-application", WorkloadAction.RESULT_APPLICATION);
  private final FeederBuilder.FileBased<String> rampResultMultipleFeeder =
      feeder(RAMP_UP, "result-multiple", WorkloadAction.RESULT_MULTIPLE);
  private final FeederBuilder.FileBased<String> rampUpdateResultFeeder =
      feeder(RAMP_UP, "update-result", WorkloadAction.UPDATE_RESULT);
  private final FeederBuilder.FileBased<String> rampUpdateListFeeder =
      feeder(RAMP_UP, "update-list", WorkloadAction.UPDATE_LIST);
  private final FeederBuilder.FileBased<String> rampCloseListFeeder =
      feeder(RAMP_UP, "close-list", WorkloadAction.CLOSE_LIST);
  private final FeederBuilder.FileBased<String> rampBulkOfficialsFeeder =
      feeder(RAMP_UP, "bulk-officials", WorkloadAction.BULK_OFFICIALS);
  private final FeederBuilder.FileBased<String> rampBulkFeesFeeder =
      feeder(RAMP_UP, "bulk-fees", WorkloadAction.BULK_FEES);
  private final FeederBuilder.FileBased<String> rampBulkUploadFeeder =
      feeder(RAMP_UP, "bulk-upload", WorkloadAction.BULK_UPLOAD);

  private final FeederBuilder.FileBased<String> measuredUpdateApplicationFeeder =
      feeder(MEASURED, "update-application", WorkloadAction.UPDATE_APPLICATION);
  private final FeederBuilder.FileBased<String> measuredAddApplicationFeeder =
      feeder(MEASURED, "add-application", WorkloadAction.ADD_APPLICATION);
  private final FeederBuilder.FileBased<String> measuredResultApplicationFeeder =
      feeder(MEASURED, "result-application", WorkloadAction.RESULT_APPLICATION);
  private final FeederBuilder.FileBased<String> measuredResultMultipleFeeder =
      feeder(MEASURED, "result-multiple", WorkloadAction.RESULT_MULTIPLE);
  private final FeederBuilder.FileBased<String> measuredUpdateResultFeeder =
      feeder(MEASURED, "update-result", WorkloadAction.UPDATE_RESULT);
  private final FeederBuilder.FileBased<String> measuredUpdateListFeeder =
      feeder(MEASURED, "update-list", WorkloadAction.UPDATE_LIST);
  private final FeederBuilder.FileBased<String> measuredCloseListFeeder =
      feeder(MEASURED, "close-list", WorkloadAction.CLOSE_LIST);
  private final FeederBuilder.FileBased<String> measuredBulkOfficialsFeeder =
      feeder(MEASURED, "bulk-officials", WorkloadAction.BULK_OFFICIALS);
  private final FeederBuilder.FileBased<String> measuredBulkFeesFeeder =
      feeder(MEASURED, "bulk-fees", WorkloadAction.BULK_FEES);
  private final FeederBuilder.FileBased<String> measuredBulkUploadFeeder =
      feeder(MEASURED, "bulk-upload", WorkloadAction.BULK_UPLOAD);

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
          }))
      .exec(session -> {
        sessionPool.recordAuthenticationJourneyCompleted();
        if (sessionPool.authenticationFailed()) {
          logPhaseOnce(
              setupFailureLogged,
              "SESSION POOL AUTHENTICATION FAILED",
              sessionPool.size() + " of " + profile.sessionPoolSize()
                  + " sessions authenticated after "
                  + sessionPool.completedAuthenticationJourneys()
                  + " configured SSO journeys completed; no spare candidates are configured");
        }
        return session;
      });

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
          && !sessionPool.authenticationFailed()
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
      .exec(session -> AppRegTraceContext.startOperation(
          session, "setup", "pooled_session_check", session.getInt(ACTOR_INDEX_SESSION_KEY)))
      .exec(pooledSessionCheck())
      .exec(AppRegTraceContext::endOperation)
      .exec(exitHereIfFailed())
      .pause(session -> profile.actionSpreadForActor(session.getInt(ACTOR_INDEX_SESSION_KEY)))
      .exec(session -> registerReadyActor(session
        .set(RAMP_ITERATION_SESSION_KEY, 0)
        .set(MEASURED_ITERATION_SESSION_KEY, 0)))
      .asLongAs(this::hasScheduledAction).on(
        exec(pace(profile.actionPace()))
          // Capture the phase after pacing, immediately before choosing the action. The stored
          // value deliberately keeps an in-flight action in the phase in which it started.
          .exec(this::assignNextAction)
          .doIf(session -> Phase.AUTHENTICATION_RAMP_UP.name().equals(
              session.getString(ACTION_PHASE_SESSION_KEY))).then(
                group(RAMP_UP_GROUP).on(workloadAction(true))
                  .exec(this::recoverRampUpFailure))
          .doIf(session -> Phase.MEASURED_STEADY_STATE.name().equals(
              session.getString(ACTION_PHASE_SESSION_KEY))).then(
                workloadAction(false).exec(this::recordMeasuredOperation))
          .exec(exitHereIfFailed()))
      // An actor that finishes its reserved plan just before the common deadline remains part of
      // the concurrent population until ramp-down instead of being reported as an early exit.
      .asLongAs(this::awaitingMeasuredDeadline).on(pause(POOL_WAIT_POLL_INTERVAL))
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
    workloadStartedNanos = System.nanoTime();
    GatewayGetRetry.resetCounts();
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
    boolean executionComplete = finalPhase == Phase.RAMP_DOWN
        && phases.targetReached()
        && sessionPool.size() == profile.sessionPoolSize()
        && phases.completedActors() == profile.concurrentUsers()
        && phases.lateCompletions() == 0;
    boolean nfrPassed = logNfrSummary(executionComplete);
    if (executionComplete && nfrPassed) {
      logPhase(
          "EXECUTION COMPLETE",
          sessionPool.size() + " authenticated sessions supported " + phases.completedActors()
              + " completed actors; " + rampActionsStarted.get() + " ramp-up and "
              + measuredActionsStarted.get()
              + " measured actions started; logical NFR assertions passed");
      printFinalWorkloadProgress();
      return;
    }
    if (executionComplete) {
      logPhase(
          "EXECUTION COMPLETE - NFR FAILED",
          sessionPool.size() + " authenticated sessions supported " + phases.completedActors()
              + " completed actors and " + measuredActionsStarted.get()
              + " measured actions; see WORKLOAD NFR SUMMARY");
      printFinalWorkloadProgress();
      throw new IllegalStateException("Workload logical NFR assertions failed");
    }
    logPhase(
        "INCOMPLETE",
        sessionPool.size() + " of " + profile.sessionPoolSize()
            + " sessions authenticated after " + sessionPool.completedAuthenticationJourneys()
            + " configured SSO journeys completed; " + phases.readyActors() + " of "
            + profile.concurrentUsers() + " actors validated; " + phases.completedActors()
            + " completed; "
            + phases.lateCompletions() + " exceeded the completion grace; final phase "
            + finalPhase);
    printFinalWorkloadProgress();
    throw new IllegalStateException("Workload did not complete its measured steady state");
  }

  private void printFinalWorkloadProgress() {
    // Repeat the final operation progress after Gatling's periodic console output so it remains
    // visible beside the elapsed time instead of scrolling out of view.
    printMeasuredOperationProgress(measuredActionsFinished.get());
    long elapsedSeconds = Duration.ofNanos(System.nanoTime() - workloadStartedNanos).toSeconds();
    long hours = elapsedSeconds / 3_600;
    long minutes = elapsedSeconds % 3_600 / 60;
    long seconds = elapsedSeconds % 60;
    System.out.println("WORKLOAD TOTAL ELAPSED TIME: "
        + (hours > 0 ? hours + "h " : "") + minutes + "m " + seconds + "s"
        + " (" + elapsedSeconds + " seconds)");
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
      logMeasuredOperationProgress(0);
    }
    return session;
  }

  private Session recordMeasuredOperation(Session session) {
    logMeasuredOperationProgress(measuredActionsFinished.incrementAndGet());
    return session;
  }

  private void logMeasuredOperationProgress(int finished) {
    int total = Math.multiplyExact(profile.concurrentUsers(), profile.actionsPerUser());
    int completedPercentage = finished * 100 / total;
    int previous = lastLoggedOperationPercentage.get();
    while (completedPercentage > previous) {
      if (lastLoggedOperationPercentage.compareAndSet(previous, completedPercentage)) {
        printMeasuredOperationProgress(finished);
        return;
      }
      previous = lastLoggedOperationPercentage.get();
    }
  }

  private void printMeasuredOperationProgress(int finished) {
    int total = Math.multiplyExact(profile.concurrentUsers(), profile.actionsPerUser());
    double remainingPercentage = Math.max(0.0, 100.0 - finished * 100.0 / total);
    int completedBars = (int) Math.min(
        OPERATION_PROGRESS_BAR_WIDTH, (long) finished * OPERATION_PROGRESS_BAR_WIDTH / total);
    System.out.println("==========");
    System.out.println("========== WORKLOAD OPERATIONS [" + "|".repeat(completedBars)
        + " ".repeat(OPERATION_PROGRESS_BAR_WIDTH - completedBars) + "] "
        + String.format(Locale.ROOT, "%.2f%% remaining (%d/%d complete)",
            remainingPercentage, finished, total));
    System.out.println("==========");
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
          .set(PLANNED_ACTION, action.key())
          .set(RAMP_ITERATION_SESSION_KEY, iteration + 1);
    }
    if (phase == Phase.MEASURED_STEADY_STATE) {
      int iteration = session.getInt(MEASURED_ITERATION_SESSION_KEY);
      WorkloadAction action = profile.actionFor(
          session.getInt(ACTOR_INDEX_SESSION_KEY), iteration);
      measuredActionsStarted.incrementAndGet();
      return session
          .set(PLANNED_ACTION, action.key())
          .set(MEASURED_ITERATION_SESSION_KEY, iteration + 1);
    }
    return session;
  }

  private boolean hasScheduledAction(Session session) {
    Phase phase = phases.currentPhase();
    if (phase == Phase.AUTHENTICATION_RAMP_UP) {
      return session.getInt(RAMP_ITERATION_SESSION_KEY) < profile.rampActionsPerUser();
    }
    if (phase == Phase.MEASURED_STEADY_STATE) {
      return session.getInt(MEASURED_ITERATION_SESSION_KEY) < profile.actionsPerUser();
    }
    return false;
  }

  private boolean awaitingMeasuredDeadline(Session session) {
    return phases.currentPhase() == Phase.MEASURED_STEADY_STATE
        && session.getInt(MEASURED_ITERATION_SESSION_KEY) >= profile.actionsPerUser();
  }

  private Session recoverRampUpFailure(Session session) {
    if (!session.isFailed()) return session;
    System.out.println("APPREG_RAMP_UP_ACTION_FAILED traceId="
        + AppRegTraceContext.currentTraceId(session) + " actor="
        + session.getInt(ACTOR_INDEX_SESSION_KEY) + " action="
        + session.getString(PLANNED_ACTION)
        + " continuation=remaining-actions-will-run");
    // Ramp-up is deliberately outside the NFR sample. Preserve Gatling request failures while
    // allowing this actor to reach the common measured window and use its isolated measured data.
    return session.markAsSucceeded();
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
    int requiredRows = RAMP_UP.equals(phase)
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
    return retryingGet(
      "AppReg pooled workload session check",
      http("AppReg pooled workload session check")
        .get("/sso/me")
        .header("Accept", "application/json"),
      jsonPath("$.authenticated").is("true"));
  }

  private ChainBuilder workloadAction(boolean rampUp) {
    return doSwitch("#{plannedAction}").on(
      onCase(WorkloadAction.UPDATE_APPLICATION.key()).then(updateApplication(rampUp)),
      onCase(WorkloadAction.ADD_APPLICATION.key()).then(addApplication(rampUp)),
      onCase(WorkloadAction.RESULT_MULTIPLE.key()).then(resultMultipleApplications(rampUp)),
      onCase(WorkloadAction.UPDATE_RESULT.key()).then(updateApplicationResult(rampUp)),
      onCase(WorkloadAction.CREATE_LIST.key()).then(measuredAction(
        rampUp, WorkloadAction.CREATE_LIST, ApplicationListCreateScenario.createApplicationList())),
      onCase(WorkloadAction.UPDATE_LIST.key()).then(updateApplicationList(rampUp)),
      onCase(WorkloadAction.CLOSE_LIST.key()).then(closeApplicationList(rampUp)),
      onCase(WorkloadAction.RESULT_APPLICATION.key()).then(resultApplication(rampUp)),
      onCase(WorkloadAction.BULK_OFFICIALS.key()).then(bulkUpdateOfficials(rampUp)),
      onCase(WorkloadAction.BULK_FEES.key()).then(bulkUpdateFees(rampUp)),
      onCase(WorkloadAction.BULK_UPLOAD.key()).then(bulkUpload(rampUp)),
      onCase(WorkloadAction.OTHER_OPERATIONS.key()).then(measuredAction(
        rampUp, WorkloadAction.OTHER_OPERATIONS, SearchScenario.searchApplicationLists()))
    );
  }

  private ChainBuilder updateApplication(boolean rampUp) {
    return feed(rampUp ? rampUpdateApplicationFeeder : measuredUpdateApplicationFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(measuredAction(
        rampUp, WorkloadAction.UPDATE_APPLICATION, UpdateApplicationScenario.updateApplication()));
  }

  private ChainBuilder addApplication(boolean rampUp) {
    return feed(rampUp ? rampAddApplicationFeeder : measuredAddApplicationFeeder)
      .exec(session -> session.set("applicationListId", session.getString("application_list_id")))
      .exec(measuredAction(
        rampUp, WorkloadAction.ADD_APPLICATION, AddApplicationScenario.addApplication()));
  }

  private ChainBuilder resultApplication(boolean rampUp) {
    return feed(rampUp ? rampResultApplicationFeeder : measuredResultApplicationFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(measuredAction(
        rampUp, WorkloadAction.RESULT_APPLICATION, ResultApplicationScenario.resultApplication()));
  }

  private ChainBuilder resultMultipleApplications(boolean rampUp) {
    return feed(rampUp ? rampResultMultipleFeeder : measuredResultMultipleFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("entryIdOne", session.getString("entry_id_one"))
        .set("entryIdTwo", session.getString("entry_id_two"))
        .set("entryIdThree", session.getString("entry_id_three")))
      .exec(measuredAction(
        rampUp,
        WorkloadAction.RESULT_MULTIPLE,
        ResultMultipleApplicationsScenario.resultMultipleApplications()));
  }

  private ChainBuilder updateApplicationResult(boolean rampUp) {
    return feed(rampUp ? rampUpdateResultFeeder : measuredUpdateResultFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(measuredAction(
        rampUp,
        WorkloadAction.UPDATE_RESULT,
        UpdateApplicationResultScenario.updateApplicationResult()));
  }

  private ChainBuilder updateApplicationList(boolean rampUp) {
    return feed(rampUp ? rampUpdateListFeeder : measuredUpdateListFeeder)
      .exec(session -> session.set("applicationListId", session.getString("application_list_id")))
      .exec(measuredAction(
        rampUp,
        WorkloadAction.UPDATE_LIST,
        UpdateApplicationListScenario.updateApplicationList()));
  }

  private ChainBuilder closeApplicationList(boolean rampUp) {
    return feed(rampUp ? rampCloseListFeeder : measuredCloseListFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(measuredAction(
        rampUp, WorkloadAction.CLOSE_LIST, CloseApplicationListScenario.closeApplicationList()));
  }

  private ChainBuilder bulkUpdateOfficials(boolean rampUp) {
    return feed(rampUp ? rampBulkOfficialsFeeder : measuredBulkOfficialsFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("entryIdOne", session.getString("entry_id_one"))
        .set("entryIdTwo", session.getString("entry_id_two"))
        .set("entryIdThree", session.getString("entry_id_three")))
      .exec(measuredAction(
        rampUp, WorkloadAction.BULK_OFFICIALS, BulkUpdateOfficialsScenario.bulkUpdateOfficials()));
  }

  private ChainBuilder bulkUpdateFees(boolean rampUp) {
    return feed(rampUp ? rampBulkFeesFeeder : measuredBulkFeesFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("entryIdOne", session.getString("entry_id_one"))
        .set("entryIdTwo", session.getString("entry_id_two"))
        .set("entryIdThree", session.getString("entry_id_three")))
      .exec(measuredAction(
        rampUp, WorkloadAction.BULK_FEES, BulkUpdateFeesScenario.bulkUpdateFees()));
  }

  private ChainBuilder bulkUpload(boolean rampUp) {
    return feed(rampUp ? rampBulkUploadFeeder : measuredBulkUploadFeeder)
      .exec(session -> session.set("applicationListId", session.getString("application_list_id")))
      .exec(measuredAction(
        rampUp, WorkloadAction.BULK_UPLOAD, BulkApplicationUploadScenario.bulkUploadApplications()));
  }

  private ChainBuilder measuredAction(
      boolean rampUp, WorkloadAction action, ChainBuilder businessAction) {
    var tracedAction = exec(session -> AppRegTraceContext.startOperation(
        session, rampUp ? RAMP_UP : MEASURED, action.key(),
        session.getInt(ACTOR_INDEX_SESSION_KEY)))
      .exec(businessAction)
      .exec(AppRegTraceContext::endOperation);
    if (rampUp) return tracedAction;
    // The timer brackets the same chain as the named Gatling group. Safe GET retry attempts and
    // their configured delay are subtracted by GatewayGetRetry before the logical p95 is asserted.
    return exec(nfrMetrics::start)
      .exec(tracedAction)
      .exec(session -> nfrMetrics.complete(action, session));
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
    System.out.println("Gatling console write period: "
        + System.getProperty("gatling.data.console.writePeriod", "5") + " seconds");
    System.out.println("Gateway GET retry policy: " + GatewayGetRetry.retries()
        + " retries after HTTP 502/504; delay "
        + seconds(GatewayGetRetry.retryDelaySeconds()));
    System.out.println("Write retry policy: disabled for POST, PUT and other non-GET requests");
    System.out.println("Bulk-upload completion polling timeout: "
        + seconds(BulkApplicationUploadScenario.pollTimeoutSeconds()));
    System.out.println("Maximum ramp-up action capacity: " + profile.maximumRampActionCount());
    System.out.println("Ramp-up allocation totals: " + profile.rampScheduledActionCounts());
    System.out.println("Measured allocation totals: " + profile.scheduledActionCounts());
    System.out.println("Allocated feeder directory: " + feederDirectory);
    System.out.println("Evidence boundary: concurrent access, not distinct users or sessions");
    System.out.println("============================================");
  }

  private boolean logNfrSummary(boolean executionComplete) {
    boolean functionalPassed = executionComplete && GatewayGetRetry.exhaustedFailures() == 0;
    boolean timingPassed = executionComplete;
    int plannedTotal = 0;
    int attemptedTotal = 0;
    var actionResults = new ArrayList<String>();
    for (var action : WorkloadAction.values()) {
      int expected = profile.scheduledActionCount(action);
      if (expected == 0) {
        actionResults.add(action.nfr() + " " + action.key()
            + ": NOT MEASURED (no scheduled samples)");
        continue;
      }
      int completed = nfrMetrics.completedActions(action);
      int attempted = nfrMetrics.attemptedActions(action);
      int failed = nfrMetrics.failedActions(action);
      long p95Millis = nfrMetrics.p95Millis(action);
      plannedTotal += expected;
      attemptedTotal += attempted;
      functionalPassed &= attempted > 0 && completed == attempted && failed == 0;
      timingPassed &= attempted > 0 && p95Millis < action.p95LimitMillis();
      boolean actionPassed = WorkloadNfrMetrics.passesNfr(
          attempted, completed, failed, p95Millis, action.p95LimitMillis());
      actionResults.add(action.nfr() + " " + action.key() + ": "
          + (actionPassed ? "PASS" : "FAIL") + " | attempted=" + attempted + "/" + expected
          + " | succeeded=" + completed + " | failed=" + failed
          + " | logical p95=" + p95Millis + " ms | limit < "
          + action.p95LimitMillis() + " ms");
    }
    boolean passed = functionalPassed && timingPassed;
    var nfr004 = profile.concurrentUsers() == 500
        ? (passed ? "PASS" : "FAIL")
        : "NOT MEASURED (configured actors: " + profile.concurrentUsers() + ")";
    var summary = new StringBuilder()
        .append("========== WORKLOAD NFR SUMMARY ==========\n")
        .append("Result classification: ")
        .append(WorkloadNfrMetrics.resultClassification(
            executionComplete, functionalPassed, timingPassed))
        .append('\n')
        .append("Functional success: ").append(functionalPassed ? "PASS" : "FAIL").append('\n')
        .append("Transient GET gateway failures observed: ")
        .append(GatewayGetRetry.transientFailures()).append('\n')
        .append("Recovered GET operations: ")
        .append(GatewayGetRetry.recoveredOperations()).append('\n')
        .append("Exhausted GET gateway failures: ")
        .append(GatewayGetRetry.exhaustedFailures()).append('\n')
        .append("Logical timing excludes recovered GET attempts and their retry delay\n")
        .append("NFR001 availability: NOT MEASURED by this Gatling run\n")
        .append("NFR005 browser-only trivial operations: NOT MEASURED by this HTTP test\n");
    actionResults.forEach(result -> summary.append(result).append('\n'));
    summary.append("Measured plan utilisation: ").append(attemptedTotal).append('/')
        .append(plannedTotal)
        .append(" operations started during the fixed window; unused final-boundary slots are")
        .append(" informational and do not override functional or p95 results\n")
        .append("NFR004 500 concurrent actors: ").append(nfr004).append('\n')
        .append("Overall asserted workload-operation verdict: ")
        .append(passed ? "PASS" : "FAIL").append('\n')
        .append("==========================================\n");
    System.out.print(summary);
    writeNfrSummary(summary.toString());
    return passed;
  }

  private static void writeNfrSummary(String summary) {
    try {
      Files.createDirectories(NFR_SUMMARY_PATH.getParent());
      Files.writeString(NFR_SUMMARY_PATH, summary, StandardCharsets.UTF_8);
      System.out.println("Retained workload NFR summary: " + NFR_SUMMARY_PATH.toAbsolutePath());
    } catch (IOException exception) {
      throw new IllegalStateException("Could not retain workload NFR summary", exception);
    }
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
    System.out.println("==========");
    System.out.println("========== WORKLOAD PHASE: " + phase + " | " + detail);
    System.out.println("==========");
  }
}
