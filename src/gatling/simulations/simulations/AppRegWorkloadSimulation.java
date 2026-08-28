package simulations;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
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
import utils.PhaseController;
import utils.PhaseController.Phase;
import utils.SsoAuthentication;
import utils.WorkloadAction;
import utils.WorkloadProfile;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.doSwitch;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.exitBlockOnFail;
import static io.gatling.javaapi.core.CoreDsl.exitHereIfFailed;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.onCase;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static utils.AppRegHttp.protocol;
import static utils.Headers.XSRF_TOKEN_COOKIE;

/**
 * Phase-based, feeder-backed AppReg workload. Each user authenticates once, starts work
 * immediately and keeps the same Gatling session through the common measured window.
 */
public class AppRegWorkloadSimulation extends Simulation {
  private static final String RAMP_UP_GROUP = "AppReg_Ramp_Up";
  private static final String ACTION_PHASE_SESSION_KEY = "workloadActionPhase";
  private static final String RAMP_ITERATION_SESSION_KEY = "workloadRampIteration";
  private static final String MEASURED_ITERATION_SESSION_KEY = "workloadMeasuredIteration";

  private final WorkloadProfile profile = WorkloadProfile.fromRuntime();
  private final PhaseController phases = new PhaseController(
      profile.concurrentUsers(),
      profile.authenticationSetupTimeout(),
      profile.steadyStateDuration(),
      Duration.ofSeconds(profile.rampDownGraceSeconds()));
  private final String feederDirectory = Path.of(System.getProperty(
      "appRegPerformanceDataDirectory", "build/workload-data")).toAbsolutePath().toString();
  private final AtomicBoolean measuredPhaseLogged = new AtomicBoolean();
  private final AtomicBoolean rampDownPhaseLogged = new AtomicBoolean();
  private final AtomicBoolean setupFailureLogged = new AtomicBoolean();
  private final AtomicBoolean completionFailureLogged = new AtomicBoolean();
  private final AtomicInteger rampActionsStarted = new AtomicInteger();
  private final AtomicInteger measuredActionsStarted = new AtomicInteger();

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
    var users = SsoAuthentication.users(profile.concurrentUsers());
    var workload = scenario("AppReg phase-based workload")
      .exitBlockOnFail().on(
        feed(users)
          .exec(AuthenticationStage.authenticate())
          .exec(getCookieValue(CookieKey(XSRF_TOKEN_COOKIE).saveAs("xsrfToken"))))
      .exec(exitHereIfFailed())
      .exec(session -> registerAuthenticatedSession(session
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

    // One finite population is intentional: replacing completed users would require account and
    // mutable-data reuse. The measured window, rather than closed injection, defines concurrency.
    setUp(workload.injectOpen(
        rampUsers(profile.concurrentUsers()).during(profile.loginRampUpDuration())))
      .protocols(protocol())
      .maxDuration(profile.maximumSimulationDuration())
      .assertions(global().successfulRequests().percent().gte(100.0));
  }

  @Override
  public void before() {
    phases.start();
    logConfiguration();
    logPhase(
        "AUTHENTICATION AND WORKLOAD RAMP-UP",
        profile.concurrentUsers() + " users injected over "
            + seconds(profile.loginRampUpSeconds()) + "; each starts work after login");
  }

  @Override
  public void after() {
    Phase finalPhase = phases.currentPhase();
    if (finalPhase == Phase.RAMP_DOWN
        && phases.targetReached()
        && phases.completedUsers() == profile.concurrentUsers()
        && phases.lateCompletions() == 0) {
      logPhase(
          "EXECUTION COMPLETE",
          phases.authenticatedUsers() + " authenticated and " + phases.completedUsers()
              + " completed sessions; " + rampActionsStarted.get() + " ramp-up and "
              + measuredActionsStarted.get() + " measured actions started");
      return;
    }
    logPhase(
        "INCOMPLETE",
        phases.authenticatedUsers() + " of " + profile.concurrentUsers()
            + " sessions authenticated; " + phases.completedUsers() + " completed; "
            + phases.lateCompletions() + " exceeded the completion grace; final phase "
            + finalPhase);
    throw new IllegalStateException("Workload did not complete its measured steady state");
  }

  private Session registerAuthenticatedSession(Session session) {
    Phase phase = phases.registerAuthenticatedSession();
    if (phase == Phase.MEASURED_STEADY_STATE) {
      logPhaseOnce(
          measuredPhaseLogged,
          "MEASURED STEADY STATE",
          profile.concurrentUsers() + " active sessions continuing work for "
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
      WorkloadAction action = profile.rampActionFor(session.getInt("accountOffset"), iteration);
      rampActionsStarted.incrementAndGet();
      return session
          .set("plannedAction", action.key())
          .set(RAMP_ITERATION_SESSION_KEY, iteration + 1);
    }
    if (phase == Phase.MEASURED_STEADY_STATE) {
      int iteration = session.getInt(MEASURED_ITERATION_SESSION_KEY);
      WorkloadAction action = profile.actionFor(session.getInt("accountOffset"), iteration);
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
      if (phases.sessionCompleted()) return session;
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
        phases.authenticatedUsers() + " of " + profile.concurrentUsers()
            + " sessions authenticated before the "
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
    System.out.println("Users: " + profile.concurrentUsers());
    System.out.println("Login injection ramp: " + seconds(profile.loginRampUpSeconds()));
    System.out.println("Authentication setup deadline: "
        + minutes(profile.authenticationSetupTimeoutMinutes()));
    System.out.println("Measured steady state: " + minutes(profile.durationMinutes()));
    System.out.println("Action pace: " + seconds(profile.actionPaceSeconds()));
    System.out.println("Ramp-down grace: " + seconds(profile.rampDownGraceSeconds()));
    System.out.println("Maximum ramp-up action capacity: " + profile.maximumRampActionCount());
    System.out.println("Ramp-up allocation totals: " + profile.rampScheduledActionCounts());
    System.out.println("Measured allocation totals: " + profile.scheduledActionCounts());
    System.out.println("Allocated feeder directory: " + feederDirectory);
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
