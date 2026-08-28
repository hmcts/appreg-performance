package simulations;

import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import java.util.Iterator;
import java.util.Map;
import scenarios.ActivityAuditReportScenario;
import scenarios.AddApplicationScenario;
import scenarios.AppRegScenario;
import scenarios.BulkApplicationUploadScenario;
import scenarios.BulkUpdateFeesScenario;
import scenarios.BulkUpdateOfficialsScenario;
import scenarios.CloseApplicationListScenario;
import scenarios.ResultApplicationScenario;
import scenarios.ResultMultipleApplicationsScenario;
import scenarios.UpdateApplicationListScenario;
import scenarios.UpdateApplicationResultScenario;
import scenarios.UpdateApplicationScenario;
import utils.AuthenticationStage;
import utils.SsoAuthentication;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.AppRegHttp.protocol;
import static utils.Environment.requiredEnvironmentVariable;
import static utils.Headers.APPREG_API_MEDIA_TYPE;
import static utils.Headers.XSRF_TOKEN_COOKIE;

/** Runs the Jenkins framework proof set sequentially with one authenticated Gatling session. */
public class FrameworkProofSimulation extends Simulation {
  private static final String EDITABLE_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_EDITABLE_LIST_ID");
  private static final String EDITABLE_ENTRY_ID = requiredEnvironmentVariable("APPREG_SEED_EDITABLE_ENTRY_ID");
  private static final String UPDATE_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_UPDATE_LIST_ID");
  private static final String CLOSE_READY_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_CLOSE_READY_LIST_ID");
  private static final String CLOSE_READY_ENTRY_ID = requiredEnvironmentVariable("APPREG_SEED_CLOSE_READY_ENTRY_ID");
  private static final String ADD_APPLICATION_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_ADD_APPLICATION_LIST_ID");
  private static final String BULK_TARGET_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_BULK_TARGET_LIST_ID");
  private static final String BULK_TARGET_ENTRY_ID_ONE = requiredEnvironmentVariable("APPREG_SEED_BULK_ENTRY_ID_1");
  private static final String BULK_TARGET_ENTRY_ID_TWO = requiredEnvironmentVariable("APPREG_SEED_BULK_ENTRY_ID_2");
  private static final String BULK_TARGET_ENTRY_ID_THREE = requiredEnvironmentVariable("APPREG_SEED_BULK_ENTRY_ID_3");
  private static final String SINGLE_RESULT_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_SINGLE_RESULT_LIST_ID");
  private static final String SINGLE_RESULT_ENTRY_ID = requiredEnvironmentVariable("APPREG_SEED_SINGLE_RESULT_ENTRY_ID");
  private static final String BULK_UPLOAD_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_BULK_UPLOAD_LIST_ID");
  private static final String BULK_FEES_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_BULK_FEES_LIST_ID");
  private static final String BULK_FEES_ENTRY_ID_ONE = requiredEnvironmentVariable("APPREG_SEED_BULK_FEES_ENTRY_ID_1");
  private static final String BULK_FEES_ENTRY_ID_TWO = requiredEnvironmentVariable("APPREG_SEED_BULK_FEES_ENTRY_ID_2");
  private static final String BULK_FEES_ENTRY_ID_THREE = requiredEnvironmentVariable("APPREG_SEED_BULK_FEES_ENTRY_ID_3");
  private static final String BULK_OFFICIALS_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_BULK_OFFICIALS_LIST_ID");
  private static final String BULK_OFFICIALS_ENTRY_ID_ONE = requiredEnvironmentVariable("APPREG_SEED_BULK_OFFICIALS_ENTRY_ID_1");
  private static final String BULK_OFFICIALS_ENTRY_ID_TWO = requiredEnvironmentVariable("APPREG_SEED_BULK_OFFICIALS_ENTRY_ID_2");
  private static final String BULK_OFFICIALS_ENTRY_ID_THREE = requiredEnvironmentVariable("APPREG_SEED_BULK_OFFICIALS_ENTRY_ID_3");
  private static final String UPDATE_RESULT_LIST_ID = requiredEnvironmentVariable("APPREG_SEED_UPDATE_RESULT_LIST_ID");
  private static final String UPDATE_RESULT_ENTRY_ID = requiredEnvironmentVariable("APPREG_SEED_UPDATE_RESULT_ENTRY_ID");

  private final Iterator<Map<String, Object>> ssoUserFeeder = SsoAuthentication.users(1);

  public FrameworkProofSimulation() {
    var proof = scenario("AppReg framework proof suite").exitBlockOnFail().on(
      feed(ssoUserFeeder)
        .exec(AuthenticationStage.authenticateFrameworkProof())
        .exec(session -> logProof(session, "01/12 - Applications list"))
        .exec(AppRegScenario.applicationsList(true))
        .exec(session -> logProof(session, "02/12 - Activity Audit report"))
        .exec(ActivityAuditReportScenario.generateActivityAuditReport())
        .exec(session -> logProof(session, "03/12 - Update Application"))
        .exec(session -> session
          .set("applicationListId", EDITABLE_LIST_ID)
          .set("applicationEntryId", EDITABLE_ENTRY_ID))
        .exec(UpdateApplicationScenario.updateApplication())
        .exec(session -> logProof(session, "04/12 - Update Application List"))
        .exec(refreshXsrfToken())
        .exec(session -> session.set("applicationListId", UPDATE_LIST_ID))
        .exec(UpdateApplicationListScenario.updateApplicationList())
        .exec(session -> logProof(session, "05/12 - Update and close Application List"))
        .exec(refreshXsrfToken())
        .exec(session -> session
          .set("applicationListId", CLOSE_READY_LIST_ID)
          .set("applicationEntryId", CLOSE_READY_ENTRY_ID))
        .exec(http("Get seeded close-ready application")
          .get("/application-lists/#{applicationListId}/entries/#{applicationEntryId}")
          .header("Accept", APPREG_API_MEDIA_TYPE)
          .check(status().is(200)))
        .exec(UpdateApplicationListScenario.updateApplicationList())
        .exec(CloseApplicationListScenario.closeApplicationList())
        .exec(session -> logProof(session, "06/12 - Add Application"))
        .exec(refreshXsrfToken())
        .exec(session -> session.set("applicationListId", ADD_APPLICATION_LIST_ID))
        .exec(AddApplicationScenario.addApplication())
        .exec(session -> logProof(session, "07/12 - Result multiple Applications"))
        .exec(refreshXsrfToken())
        .exec(session -> session
          .set("applicationListId", BULK_TARGET_LIST_ID)
          .set("entryIdOne", BULK_TARGET_ENTRY_ID_ONE)
          .set("entryIdTwo", BULK_TARGET_ENTRY_ID_TWO)
          .set("entryIdThree", BULK_TARGET_ENTRY_ID_THREE))
        .exec(ResultMultipleApplicationsScenario.resultMultipleApplications())
        .exec(session -> logProof(session, "08/12 - Result Application"))
        .exec(refreshXsrfToken())
        .exec(session -> session
          .set("applicationListId", SINGLE_RESULT_LIST_ID)
          .set("applicationEntryId", SINGLE_RESULT_ENTRY_ID))
        .exec(ResultApplicationScenario.resultApplication())
        .exec(session -> logProof(session, "09/12 - Bulk Application upload"))
        .exec(refreshXsrfToken())
        .exec(session -> session.set("applicationListId", BULK_UPLOAD_LIST_ID))
        .exec(BulkApplicationUploadScenario.bulkUploadApplications())
        .exec(session -> logProof(session, "10/12 - Bulk update fees"))
        .exec(refreshXsrfToken())
        .exec(session -> session
          .set("applicationListId", BULK_FEES_LIST_ID)
          .set("entryIdOne", BULK_FEES_ENTRY_ID_ONE)
          .set("entryIdTwo", BULK_FEES_ENTRY_ID_TWO)
          .set("entryIdThree", BULK_FEES_ENTRY_ID_THREE))
        .exec(BulkUpdateFeesScenario.bulkUpdateFees())
        .exec(session -> logProof(session, "11/12 - Bulk update officials"))
        .exec(refreshXsrfToken())
        .exec(session -> session
          .set("applicationListId", BULK_OFFICIALS_LIST_ID)
          .set("entryIdOne", BULK_OFFICIALS_ENTRY_ID_ONE)
          .set("entryIdTwo", BULK_OFFICIALS_ENTRY_ID_TWO)
          .set("entryIdThree", BULK_OFFICIALS_ENTRY_ID_THREE))
        .exec(BulkUpdateOfficialsScenario.bulkUpdateOfficials())
        .exec(session -> logProof(session, "12/12 - Update Application Result"))
        .exec(refreshXsrfToken())
        .exec(session -> session
          .set("applicationListId", UPDATE_RESULT_LIST_ID)
          .set("applicationEntryId", UPDATE_RESULT_ENTRY_ID))
        .exec(UpdateApplicationResultScenario.updateApplicationResult()));

    setUp(proof.injectOpen(atOnceUsers(1)))
      .protocols(protocol())
      .assertions(global().successfulRequests().percent().gte(100.0));
  }

  private static ActionBuilder refreshXsrfToken() {
    return getCookieValue(CookieKey(XSRF_TOKEN_COOKIE).saveAs("xsrfToken"));
  }

  private static Session logProof(Session session, String proofName) {
    System.out.printf("%n==================== FRAMEWORK PROOF %s ====================%n", proofName);
    return session;
  }

  @Override
  public void before() {
    System.out.printf("""

      ================================================================================
      FRAMEWORK PROOF CONFIGURATION
      Authenticated users : 1
      SSO journeys        : 1
      Business proofs     : 12
      Session reuse       : enabled
      ================================================================================
      %n""");
  }
}
