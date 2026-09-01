package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import utils.Headers;
import utils.WorkloadAction;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.RawFileBodyPart;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.GatewayGetRetry.retryingGet;

/** Replays the recorded UI flow for asynchronously uploading Application Entries from CSV. */
public final class BulkApplicationUploadScenario {
  private static final String CSV_FILE = "uploads/bulk-upload-entries_Legacy respondent name columns_ApplicationCodesFeeReqIsYes_Staging.csv";

  private BulkApplicationUploadScenario() {}

  public static ChainBuilder bulkUploadApplications() {
    return group(WorkloadAction.BULK_UPLOAD.groupName()).on(
      exec(retryingGet(
        "Open application list for bulk upload",
        http("Open application list for bulk upload")
          .get("/application-lists/#{applicationListId}/entries")
          .queryParam("pageNumber", "0").queryParam("pageSize", "10").queryParam("sort", "sequenceNumber,asc")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE)))
      .exec(http("Start bulk application upload")
        .post("/application-lists/#{applicationListId}/entries/bulk-import")
        .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
        .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
        .bodyPart(RawFileBodyPart("file", CSV_FILE).contentType("text/csv"))
        .check(status().is(202)).check(jsonPath("$.id").saveAs("bulkUploadJobId")))
      .exec(retryingGet(
        "Check bulk upload completion",
        http("Check bulk upload completion")
          .get("/jobs/#{bulkUploadJobId}")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE),
        jsonPath("$.status").saveAs("bulkUploadStatus")))
      .asLongAs(session -> "PROCESSING".equals(session.getString("bulkUploadStatus"))).on(
        pause(2)
          .exec(retryingGet(
            "Check bulk upload completion",
            http("Check bulk upload completion")
              .get("/jobs/#{bulkUploadJobId}")
              .header("Accept", Headers.APPREG_API_MEDIA_TYPE),
            jsonPath("$.status").saveAs("bulkUploadStatus"))))
      .exec(session -> {
        var uploadStatus = session.getString("bulkUploadStatus");
        return "SUCCEEDED".equals(uploadStatus) || "COMPLETED".equals(uploadStatus)
          ? session
          : session.markAsFailed();
      })
    );
  }
}
