package simulations;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.Simulation;
import java.nio.file.Path;
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
import utils.SsoAuthentication;
import utils.WorkloadAction;
import utils.WorkloadProfile;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.doSwitch;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.onCase;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.repeat;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static utils.AppRegHttp.protocol;
import static utils.Headers.XSRF_TOKEN_COOKIE;

/**
 * Bounded, feeder-backed AppReg workload. It is intentionally separate from all ProofSimulation
 * classes: each user logs in once, receives a deterministic, isolated action sequence and
 * performs one planned action per minute for the configured duration.
 */
public class AppRegWorkloadSimulation extends Simulation {
  private final WorkloadProfile profile = WorkloadProfile.fromRuntime();
  private final String feederDirectory = Path.of(System.getProperty(
      "appRegPerformanceDataDirectory", "build/workload-data")).toAbsolutePath().toString();

  private final FeederBuilder.FileBased<String> updateApplicationFeeder = feeder("update-application", WorkloadAction.UPDATE_APPLICATION);
  private final FeederBuilder.FileBased<String> addApplicationFeeder = feeder("add-application", WorkloadAction.ADD_APPLICATION);
  private final FeederBuilder.FileBased<String> resultApplicationFeeder = feeder("result-application", WorkloadAction.RESULT_APPLICATION);
  private final FeederBuilder.FileBased<String> resultMultipleFeeder = feeder("result-multiple", WorkloadAction.RESULT_MULTIPLE);
  private final FeederBuilder.FileBased<String> updateResultFeeder = feeder("update-result", WorkloadAction.UPDATE_RESULT);
  private final FeederBuilder.FileBased<String> updateListFeeder = feeder("update-list", WorkloadAction.UPDATE_LIST);
  private final FeederBuilder.FileBased<String> closeListFeeder = feeder("close-list", WorkloadAction.CLOSE_LIST);
  private final FeederBuilder.FileBased<String> bulkOfficialsFeeder = feeder("bulk-officials", WorkloadAction.BULK_OFFICIALS);
  private final FeederBuilder.FileBased<String> bulkFeesFeeder = feeder("bulk-fees", WorkloadAction.BULK_FEES);
  private final FeederBuilder.FileBased<String> bulkUploadFeeder = feeder("bulk-upload", WorkloadAction.BULK_UPLOAD);

  public AppRegWorkloadSimulation() {
    var httpProtocol = protocol();
    var users = SsoAuthentication.users(profile.concurrentUsers());

    var workload = scenario("AppReg weighted workload")
      .exitBlockOnFail().on(
        feed(users)
          .exec(SsoAuthentication.login())
          .exec(getCookieValue(CookieKey(XSRF_TOKEN_COOKIE).saveAs("xsrfToken")))
          .repeat(profile.actionsPerUser(), "workloadIteration").on(
            exec(session -> session.set("plannedAction", profile.actionFor(
                session.getInt("accountOffset"), session.getInt("workloadIteration")).key()))
              .exec(doSwitch("#{plannedAction}").on(
                onCase(WorkloadAction.UPDATE_APPLICATION.key()).then(updateApplication()),
                onCase(WorkloadAction.ADD_APPLICATION.key()).then(addApplication()),
                onCase(WorkloadAction.RESULT_MULTIPLE.key()).then(resultMultipleApplications()),
                onCase(WorkloadAction.UPDATE_RESULT.key()).then(updateApplicationResult()),
                onCase(WorkloadAction.CREATE_LIST.key()).then(ApplicationListCreateScenario.createApplicationList()),
                onCase(WorkloadAction.UPDATE_LIST.key()).then(updateApplicationList()),
                onCase(WorkloadAction.CLOSE_LIST.key()).then(closeApplicationList()),
                onCase(WorkloadAction.RESULT_APPLICATION.key()).then(resultApplication()),
                onCase(WorkloadAction.BULK_OFFICIALS.key()).then(bulkUpdateOfficials()),
                onCase(WorkloadAction.BULK_FEES.key()).then(bulkUpdateFees()),
                onCase(WorkloadAction.BULK_UPLOAD.key()).then(bulkUpload()),
                // Search represents the non-destructive Other UI Operations allocation. Reporting
                // remains a separate benchmark until a reporting workload expectation is agreed.
                onCase(WorkloadAction.OTHER_OPERATIONS.key()).then(SearchScenario.searchApplicationLists())
              ))
              .exec(pace(60))
          )
      );

    // This is a finite, feeder-backed run: start each allocated account once and allow its
    // bounded journey to finish. A closed injection would replace a completed user to preserve
    // concurrency, requiring additional accounts and risking account reuse.
    setUp(workload.injectOpen(
        rampUsers(profile.concurrentUsers()).during(profile.loginRampUpSeconds())
      ))
      .protocols(httpProtocol)
      // This is a benchmark, so it has no response-time NFR. Functional HTTP failures remain
      // failures: do not mask platform or application errors behind an arbitrary success rate.
      .assertions(global().successfulRequests().percent().gte(100.0));
  }

  @Override
  public void before() {
    System.out.println("Workload profile: " + profile.name() + ", " + profile.concurrentUsers()
        + " users, " + profile.actionsPerUser() + " actions per user, "
        + profile.actionPlan().size() + " deterministic action slots");
    System.out.println("Scheduled action totals: " + profile.scheduledActionCounts());
    System.out.println("Allocated feeder directory: " + feederDirectory);
    System.out.println("SSO is limited to " + WorkloadProfile.minimumLoginRampUpSeconds(profile.concurrentUsers())
        + " seconds minimum for " + profile.concurrentUsers() + " accounts at 10 logins per second.");
  }

  private FeederBuilder.FileBased<String> feeder(String action, WorkloadAction scheduledAction) {
    String path = Path.of(feederDirectory, action + ".csv").toString();
    FeederBuilder.FileBased<String> feeder = csv(path).queue();
    int requiredRows = profile.scheduledActionCount(scheduledAction);
    if (feeder.recordsCount() != requiredRows) {
      throw new IllegalArgumentException(
          "Feeder " + path + " has " + feeder.recordsCount() + " rows; " + profile.name()
              + " requires exactly " + requiredRows + " for " + action);
    }
    return feeder;
  }

  private ChainBuilder updateApplication() {
    return feed(updateApplicationFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(UpdateApplicationScenario.updateApplication());
  }

  private ChainBuilder addApplication() {
    return feed(addApplicationFeeder)
      .exec(session -> session.set("applicationListId", session.getString("application_list_id")))
      .exec(AddApplicationScenario.addApplication());
  }

  private ChainBuilder resultApplication() {
    return feed(resultApplicationFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(ResultApplicationScenario.resultApplication());
  }

  private ChainBuilder resultMultipleApplications() {
    return feed(resultMultipleFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("entryIdOne", session.getString("entry_id_one"))
        .set("entryIdTwo", session.getString("entry_id_two"))
        .set("entryIdThree", session.getString("entry_id_three")))
      .exec(ResultMultipleApplicationsScenario.resultMultipleApplications());
  }

  private ChainBuilder updateApplicationResult() {
    return feed(updateResultFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(UpdateApplicationResultScenario.updateApplicationResult());
  }

  private ChainBuilder updateApplicationList() {
    return feed(updateListFeeder)
      .exec(session -> session.set("applicationListId", session.getString("application_list_id")))
      .exec(UpdateApplicationListScenario.updateApplicationList());
  }

  private ChainBuilder closeApplicationList() {
    return feed(closeListFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("applicationEntryId", session.getString("application_entry_id")))
      .exec(CloseApplicationListScenario.closeApplicationList());
  }

  private ChainBuilder bulkUpdateOfficials() {
    return feed(bulkOfficialsFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("entryIdOne", session.getString("entry_id_one"))
        .set("entryIdTwo", session.getString("entry_id_two"))
        .set("entryIdThree", session.getString("entry_id_three")))
      .exec(BulkUpdateOfficialsScenario.bulkUpdateOfficials());
  }

  private ChainBuilder bulkUpdateFees() {
    return feed(bulkFeesFeeder)
      .exec(session -> session
        .set("applicationListId", session.getString("application_list_id"))
        .set("entryIdOne", session.getString("entry_id_one"))
        .set("entryIdTwo", session.getString("entry_id_two"))
        .set("entryIdThree", session.getString("entry_id_three")))
      .exec(BulkUpdateFeesScenario.bulkUpdateFees());
  }

  private ChainBuilder bulkUpload() {
    return feed(bulkUploadFeeder)
      .exec(session -> session.set("applicationListId", session.getString("application_list_id")))
      .exec(BulkApplicationUploadScenario.bulkUploadApplications());
  }
}
