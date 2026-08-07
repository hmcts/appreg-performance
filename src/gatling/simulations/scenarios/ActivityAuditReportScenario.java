package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
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
  private static final String ACTIVITY_TYPE = System.getProperty("appRegActivityAuditReportActivity", "ADD_APPLICATION");
  private static final String DATE_FROM = System.getProperty(
    "appRegActivityAuditReportDateFrom", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
  private static final String DATE_TO = System.getProperty(
    "appRegActivityAuditReportDateTo", LocalDate.now(ZoneOffset.UTC).toString());

  private ActivityAuditReportScenario() {}

  public static ChainBuilder generateActivityAuditReport() {
    return group("AppReg_090_Reports_Activity_Audit").on(
      exec(session -> session
        .set("activityAuditReportDateFrom", DATE_FROM)
        .set("activityAuditReportDateTo", DATE_TO)
        .set("activityAuditReportActivity", ACTIVITY_TYPE))
        .exec(http("Reports page")
          .get("/reports")
          .headers(COMMON_HEADER)
          .check(status().is(200)))
        .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("xsrfToken")))
        .exec(http("Start Activity Audit report")
          .post("/reports/activity-audit/jobs")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody("""
            {"dateFrom":"#{activityAuditReportDateFrom}","dateTo":"#{activityAuditReportDateTo}","activityTypes":["#{activityAuditReportActivity}"]}
            """))
          .check(status().is(202))
          .check(jsonPath("$.id").saveAs("activityAuditReportJobId")))
        .exec(checkReportCompletion())
        .asLongAs(session -> "PROCESSING".equals(session.getString("activityAuditReportStatus"))).on(
          pause(2).exec(checkReportCompletion()))
        .exec(session -> {
          var reportStatus = session.getString("activityAuditReportStatus");
          return "SUCCEEDED".equals(reportStatus) || "COMPLETED".equals(reportStatus)
            ? session
            : session.markAsFailed();
        })
        .exec(http("Download Activity Audit report")
          .get("/reports/jobs/#{activityAuditReportJobId}/download")
          .header("Accept", "text/csv")
          .check(status().is(200)))
    );
  }

  private static ChainBuilder checkReportCompletion() {
    return exec(http("Check Activity Audit report completion")
      .get("/jobs/#{activityAuditReportJobId}")
      .header("Accept", "application/vnd.hmcts.appreg.v1+json")
      .check(status().is(200))
      .check(jsonPath("$.status").saveAs("activityAuditReportStatus")));
  }
}
