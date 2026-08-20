package scenarios;

import io.gatling.javaapi.core.ChainBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.RawFileBodyPart;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.DiagnosticLogging.logIfStatusAtLeast;

/** Replays the recorded UI flow for asynchronously uploading Application Entries from CSV. */
public final class BulkApplicationUploadScenario {
  private static final String CSV_FILE = "uploads/bulk-upload-entries_Legacy respondent name columns_ApplicationCodesFeeReqIsYes_Staging.csv";

  private BulkApplicationUploadScenario() {}

  public static ChainBuilder bulkUploadApplications() {
    return group("AppReg_085_Applications_Bulk_Upload").on(
      exec(http("Open application list for bulk upload")
        .get("/application-lists/#{applicationListId}/entries")
        .queryParam("pageNumber", "0").queryParam("pageSize", "10").queryParam("sort", "sequenceNumber,asc")
        .header("Accept", "application/vnd.hmcts.appreg.v1+json")
        .transformResponse(logIfStatusAtLeast("Open application list for bulk upload", 400))
        .check(status().is(200)))
      .exec(http("Start bulk application upload")
        .post("/application-lists/#{applicationListId}/entries/bulk-import")
        .header("Accept", "application/vnd.hmcts.appreg.v1+json")
        .header("X-XSRF-TOKEN", "#{xsrfToken}")
        .bodyPart(RawFileBodyPart("file", CSV_FILE).contentType("text/csv"))
        .transformResponse(logIfStatusAtLeast("Start bulk application upload", 400))
        .check(status().is(202)).check(jsonPath("$.id").saveAs("bulkUploadJobId")))
      .exec(http("Check bulk upload completion")
        .get("/jobs/#{bulkUploadJobId}")
        .header("Accept", "application/vnd.hmcts.appreg.v1+json")
        .transformResponse(logIfStatusAtLeast("Check bulk upload completion", 400))
        .check(status().is(200)).check(jsonPath("$.status").saveAs("bulkUploadStatus")))
      .asLongAs(session -> "PROCESSING".equals(session.getString("bulkUploadStatus"))).on(
        pause(2)
          .exec(http("Check bulk upload completion")
            .get("/jobs/#{bulkUploadJobId}")
            .header("Accept", "application/vnd.hmcts.appreg.v1+json")
            .transformResponse(logIfStatusAtLeast("Check bulk upload completion", 400))
            .check(status().is(200)).check(jsonPath("$.status").saveAs("bulkUploadStatus"))))
      .exec(session -> {
        var uploadStatus = session.getString("bulkUploadStatus");
        return "SUCCEEDED".equals(uploadStatus) || "COMPLETED".equals(uploadStatus)
          ? session
          : session.markAsFailed();
      })
    );
  }
}
