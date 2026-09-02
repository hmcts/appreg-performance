package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.Duration;
import java.util.Set;
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
  private static final String POLL_DEADLINE_SESSION_KEY = "bulkUploadPollDeadlineNanos";
  private static final int POLL_TIMEOUT_SECONDS = positiveIntegerProperty(
      "appRegBulkUploadPollTimeoutSeconds", 30);
  private static final Set<String> IN_PROGRESS_STATUSES = Set.of(
      "RECEIVED", "VALIDATING", "PROCESSING");

  private BulkApplicationUploadScenario() {}

  public static ChainBuilder bulkUploadApplications() {
    return group(WorkloadAction.BULK_UPLOAD.groupName()).on(
      // A failed start must not poll a job ID retained from an earlier actor iteration.
      exec(session -> session.removeAll(
        "bulkUploadJobId", "bulkUploadStatus", POLL_DEADLINE_SESSION_KEY))
      .exec(exitBlockOnFail().on(
        exec(retryingGet(
          "Open application list for bulk upload",
          http("Open application list for bulk upload")
            .get("/application-lists/#{applicationListId}/entries")
            .queryParam("pageNumber", "0")
            .queryParam("pageSize", "10")
            .queryParam("sort", "sequenceNumber,asc")
            .header("Accept", Headers.APPREG_API_MEDIA_TYPE)))
      .exec(http("Start bulk application upload")
        .post("/application-lists/#{applicationListId}/entries/bulk-import")
        .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
        .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
        .bodyPart(RawFileBodyPart("file", CSV_FILE).contentType("text/csv"))
        .check(status().is(202)).check(jsonPath("$.id").saveAs("bulkUploadJobId")))
      .exec(session -> session.set(
        POLL_DEADLINE_SESSION_KEY,
        System.nanoTime() + Duration.ofSeconds(POLL_TIMEOUT_SECONDS).toNanos()))
      .exec(retryingGet(
        "Check bulk upload completion",
        http("Check bulk upload completion")
          .get("/jobs/#{bulkUploadJobId}")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE),
        jsonPath("$.status").saveAs("bulkUploadStatus")))
      .asLongAs(session -> isInProgress(session.getString("bulkUploadStatus"))
          && System.nanoTime() < session.getLong(POLL_DEADLINE_SESSION_KEY)).on(
        pause(2)
          .exec(retryingGet(
            "Check bulk upload completion",
            http("Check bulk upload completion")
              .get("/jobs/#{bulkUploadJobId}")
              .header("Accept", Headers.APPREG_API_MEDIA_TYPE),
            jsonPath("$.status").saveAs("bulkUploadStatus"))))
        .exec(session -> {
          var uploadStatus = session.getString("bulkUploadStatus");
          if ("SUCCEEDED".equals(uploadStatus) || "COMPLETED".equals(uploadStatus)) {
            return session;
          }
          if (isInProgress(uploadStatus)) {
            System.out.println("APPREG_BUSINESS_ACTION_FAILED action=bulk_upload reason=poll_timeout"
                + " lastStatus=" + uploadStatus + " timeoutSeconds=" + POLL_TIMEOUT_SECONDS);
          } else {
            System.out.println("APPREG_BUSINESS_ACTION_FAILED action=bulk_upload reason=terminal_status"
                + " terminalStatus=" + uploadStatus);
          }
          return session.markAsFailed();
        })))
    );
  }

  public static int pollTimeoutSeconds() {
    return POLL_TIMEOUT_SECONDS;
  }

  private static boolean isInProgress(String status) {
    return status != null && IN_PROGRESS_STATUSES.contains(status);
  }

  /** Dependency-free guard against treating a documented transitional status as terminal. */
  public static void selfCheck() {
    if (!isInProgress("RECEIVED")
        || !isInProgress("VALIDATING")
        || !isInProgress("PROCESSING")
        || isInProgress("COMPLETED")
        || isInProgress("FAILED")
        || isInProgress(null)) {
      throw new IllegalStateException("Bulk-upload polling status classification failed");
    }
  }

  private static int positiveIntegerProperty(String name, int defaultValue) {
    var value = System.getProperty(name);
    if (value == null || value.isBlank()) return defaultValue;
    try {
      int parsed = Integer.parseInt(value);
      if (parsed > 0 && parsed <= 3600) return parsed;
    } catch (NumberFormatException ignored) {
      // Report the same actionable configuration error for malformed and out-of-range values.
    }
    throw new IllegalArgumentException(name + " must be an integer from 1 to 3600 seconds");
  }
}
