package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import utils.Headers;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.exitBlockOnFail;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.Headers.COMMON_HEADER;

/** Generates and downloads an Activity Audit report using AppReg's asynchronous report-job flow. */
public final class ActivityAuditReportScenario {
  private static final String POLL_DEADLINE = "activityAuditReportPollDeadlineNanos";
  private static final Set<String> IN_PROGRESS_STATUSES = Set.of("RECEIVED", "VALIDATING", "PROCESSING");
  private static final int POLL_TIMEOUT_SECONDS = pollTimeoutSeconds(
    System.getProperty("appRegActivityAuditReportPollTimeoutSeconds", "120"));
  private static final String ACTIVITY_TYPE = System.getProperty("appRegActivityAuditReportActivity", "ADD_APPLICATION");
  private static final String DATE_FROM = System.getProperty(
    "appRegActivityAuditReportDateFrom", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
  private static final String DATE_TO = System.getProperty(
    "appRegActivityAuditReportDateTo", LocalDate.now(ZoneOffset.UTC).toString());

  private ActivityAuditReportScenario() {}

  public static ChainBuilder generateActivityAuditReport() {
    return group("AppReg_090_Reports_Activity_Audit").on(
      // A failed report start must not poll or download a job retained from an earlier run.
      exec(session -> session
        .removeAll("activityAuditReportJobId", "activityAuditReportStatus", POLL_DEADLINE)
        .set("activityAuditReportDateFrom", DATE_FROM)
        .set("activityAuditReportDateTo", DATE_TO)
        .set("activityAuditReportActivity", ACTIVITY_TYPE))
      .exec(exitBlockOnFail().on(
        exec(http("Reports page")
          .get("/reports")
          .headers(COMMON_HEADER)
          .check(status().is(200)))
        .exec(getCookieValue(CookieKey(Headers.XSRF_TOKEN_COOKIE).saveAs("xsrfToken")))
        .exec(http("Start Activity Audit report")
          .post("/reports/activity-audit/jobs")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
          .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
          .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
          .body(StringBody("""
            {"dateFrom":"#{activityAuditReportDateFrom}","dateTo":"#{activityAuditReportDateTo}","activityTypes":["#{activityAuditReportActivity}"]}
            """))
          .check(status().is(202))
          .check(jsonPath("$.id").saveAs("activityAuditReportJobId")))
        .exec(session -> session.set(
          POLL_DEADLINE, System.nanoTime() + Duration.ofSeconds(POLL_TIMEOUT_SECONDS).toNanos()))
        .exec(checkReportCompletion())
        .asLongAs(session -> isInProgress(session.getString("activityAuditReportStatus"))
            && System.nanoTime() < session.getLong(POLL_DEADLINE)).on(
          pause(2).exec(checkReportCompletion()))
        .exec(session -> {
          var reportStatus = session.getString("activityAuditReportStatus");
          if ("COMPLETED".equals(reportStatus)) {
            return session;
          }
          System.out.println("APPREG_BUSINESS_ACTION_FAILED action=activity_audit_report"
            + " lastStatus=" + reportStatus + " timeoutSeconds=" + POLL_TIMEOUT_SECONDS);
          return session.markAsFailed();
        })
        .exec(http("Download Activity Audit report")
          .get("/reports/jobs/#{activityAuditReportJobId}/download")
          .header("Accept", "text/csv")
          .check(status().is(200)))))
    );
  }

  private static boolean isInProgress(String status) {
    return status != null && IN_PROGRESS_STATUSES.contains(status);
  }

  private static int pollTimeoutSeconds(String value) {
    try {
      int seconds = Integer.parseInt(value);
      if (seconds > 0 && seconds <= 3600) return seconds;
    } catch (NumberFormatException ignored) {
      // Use the same error for malformed and out-of-range values.
    }
    throw new IllegalArgumentException("appRegActivityAuditReportPollTimeoutSeconds must be from 1 to 3600");
  }

  /** Dependency-free check of the documented job states and timeout bounds. */
  public static void main(String[] args) {
    for (var status : new String[] {"RECEIVED", "VALIDATING", "PROCESSING"}) {
      if (!isInProgress(status)) throw new AssertionError("Must keep polling " + status);
    }
    for (var status : new String[] {"COMPLETED", "FAILED", "UNKNOWN", null}) {
      if (isInProgress(status)) throw new AssertionError("Must stop polling " + status);
    }
    for (var value : new String[] {"1", "120", "3600"}) {
      if (pollTimeoutSeconds(value) != Integer.parseInt(value)) throw new AssertionError(value);
    }
    for (var value : new String[] {"0", "-1", "3601", "invalid", "2147483648"}) {
      try {
        pollTimeoutSeconds(value);
        throw new AssertionError("Accepted invalid timeout " + value);
      } catch (IllegalArgumentException expected) {
        // Invalid configuration must fail before starting a journey.
      }
    }
    System.out.println("Activity Audit report polling self-check passed");
  }

  private static ChainBuilder checkReportCompletion() {
    return exec(http("Check Activity Audit report completion")
      .get("/jobs/#{activityAuditReportJobId}")
      .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
      .check(status().is(200))
      .check(jsonPath("$.status").saveAs("activityAuditReportStatus")));
  }
}
