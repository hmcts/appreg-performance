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
import utils.Environment;
import utils.SsoAuthentication;
import utils.WorkloadProfile;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.during;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.pace;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * Bounded, feeder-backed AppReg workload. It is intentionally separate from all ProofSimulation
 * classes: each user logs in once, receives queued synthetic data for each destructive action,
 * and repeats weighted business actions for the configured profile duration.
 */
public class AppRegWorkloadSimulation extends Simulation {
  private static final double UPDATE_APPLICATION_WEIGHT = 39.32;
  private static final double ADD_APPLICATION_WEIGHT = 18.47;
  private static final double RESULT_MULTIPLE_WEIGHT = 7.75;
  private static final double UPDATE_RESULT_WEIGHT = 7.28;
  private static final double CREATE_LIST_WEIGHT = 7.19;
  private static final double UPDATE_LIST_WEIGHT = 6.039;
  private static final double CLOSE_LIST_WEIGHT = 0.671;
  private static final double RESULT_APPLICATION_WEIGHT = 4.45;
  private static final double BULK_OFFICIALS_WEIGHT = 4.21;
  private static final double BULK_FEES_WEIGHT = 1.38;
  private static final double BULK_UPLOAD_WEIGHT = 0.67;
  private static final double OTHER_OPERATIONS_WEIGHT = 2.57;

  private final WorkloadProfile profile = WorkloadProfile.fromRuntime();
  private final String feederDirectory = Path.of(System.getProperty(
      "appRegPerformanceDataDirectory", "build/workload-data")).toAbsolutePath().toString();

  private final FeederBuilder.FileBased<String> updateApplicationFeeder = feeder("update-application", profile.updateApplicationCount());
  private final FeederBuilder.FileBased<String> addApplicationFeeder = feeder("add-application", profile.addApplicationCount());
  private final FeederBuilder.FileBased<String> resultApplicationFeeder = feeder("result-application", profile.resultApplicationCount());
  private final FeederBuilder.FileBased<String> resultMultipleFeeder = feeder("result-multiple", profile.resultMultipleCount());
  private final FeederBuilder.FileBased<String> updateResultFeeder = feeder("update-result", profile.updateResultCount());
  private final FeederBuilder.FileBased<String> updateListFeeder = feeder("update-list", profile.updateListCount());
  private final FeederBuilder.FileBased<String> closeListFeeder = feeder("close-list", profile.closeListCount());
  private final FeederBuilder.FileBased<String> bulkOfficialsFeeder = feeder("bulk-officials", profile.bulkOfficialsCount());
  private final FeederBuilder.FileBased<String> bulkFeesFeeder = feeder("bulk-fees", profile.bulkFeesCount());
  private final FeederBuilder.FileBased<String> bulkUploadFeeder = feeder("bulk-upload", profile.bulkUploadCount());

  public AppRegWorkloadSimulation() {
    var httpProtocol = http.baseUrl(Environment.BASE_URL).doNotTrackHeader("1").inferHtmlResources().silentResources();
    var users = SsoAuthentication.users(profile.concurrentUsers());

    var workload = scenario("AppReg weighted workload")
      .exitBlockOnFail().on(
        feed(users)
          .exec(SsoAuthentication.login())
          .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("xsrfToken")))
          .during(profile.durationMinutes() * 60L, false).on(
            randomSwitch().on(
              percent(UPDATE_APPLICATION_WEIGHT).then(updateApplication()),
              percent(ADD_APPLICATION_WEIGHT).then(addApplication()),
              percent(RESULT_MULTIPLE_WEIGHT).then(resultMultipleApplications()),
              percent(UPDATE_RESULT_WEIGHT).then(updateApplicationResult()),
              percent(CREATE_LIST_WEIGHT).then(ApplicationListCreateScenario.createApplicationList()),
              percent(UPDATE_LIST_WEIGHT).then(updateApplicationList()),
              percent(CLOSE_LIST_WEIGHT).then(closeApplicationList()),
              percent(RESULT_APPLICATION_WEIGHT).then(resultApplication()),
              percent(BULK_OFFICIALS_WEIGHT).then(bulkUpdateOfficials()),
              percent(BULK_FEES_WEIGHT).then(bulkUpdateFees()),
              percent(BULK_UPLOAD_WEIGHT).then(bulkUpload()),
              // Search represents the non-destructive Other UI Operations allocation. Reporting
              // remains a separate benchmark until its NFR and workload expectation are agreed.
              percent(OTHER_OPERATIONS_WEIGHT).then(SearchScenario.searchApplicationLists())
            ).exec(pace(60))
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
    System.out.println("Workload profile: " + profile);
    System.out.println("Allocated feeder directory: " + feederDirectory);
    System.out.println("SSO is limited to " + WorkloadProfile.minimumLoginRampUpSeconds(profile.concurrentUsers())
        + " seconds minimum for " + profile.concurrentUsers() + " accounts at 10 logins per second.");
  }

  private FeederBuilder.FileBased<String> feeder(String action, int requiredRows) {
    String path = Path.of(feederDirectory, action + ".csv").toString();
    FeederBuilder.FileBased<String> feeder = csv(path).queue();
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
