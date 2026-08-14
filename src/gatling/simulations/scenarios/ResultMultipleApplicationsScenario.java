package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.ApplicationListFailureLogger.STATUS_SESSION_KEY;
import static utils.ApplicationListFailureLogger.logFailure;

/**
 * Replays the meaningful HTTP sequence from the Result selected UI journey for several Applications
 * in the Application List held in the Gatling session. The caller must provide eligible, unresulted
 * entries as entryIdOne to entryIdThree.
 */
public final class ResultMultipleApplicationsScenario {
  private static final String RESULT_CODE = System.getProperty("appRegBulkResultCode", "RTC");
  private static final String COURTHOUSE = System.getProperty("appRegBulkResultCourthouse", "London Crown Court");

  private ResultMultipleApplicationsScenario() {}

  public static ChainBuilder resultMultipleApplications() {
    return group("AppReg_060_Applications_Bulk_Result").on(
      exec(session -> session.set(
        "bulkResultDate",
        LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
        .set("bulkResultCodeDate", LocalDate.now().toString()))
        .exec(http("Open application list")
          .get("/application-lists/#{applicationListId}")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "10")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().saveAs(STATUS_SESSION_KEY))
          .check(status().is(200)))
        .exec(logFailure("Result Multiple Applications", "Open application list"))
        .exec(http("Get application list entries")
          .get("/application-lists/#{applicationListId}/entries")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "10")
          .queryParam("sort", "sequenceNumber,asc")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Preview result for selected applications")
          .post("/application-lists/#{applicationListId}/entries/bulk-action-preview")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody(selectionBody()))
          .check(status().is(200)))
        .exec(http("Get result codes")
          .get("/result-codes")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "100")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Get selected result-code details")
          .get("/result-codes/" + RESULT_CODE)
          .queryParam("date", "#{bulkResultCodeDate}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Apply result to selected applications")
          .post("/application-lists/#{applicationListId}/entries/results")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody(resultBody()))
          .check(status().is(200))
          .check(jsonPath("$[0].entryId").isEL("#{entryIdOne}"))
          .check(jsonPath("$[1].entryId").isEL("#{entryIdTwo}"))
          .check(jsonPath("$[2].entryId").isEL("#{entryIdThree}")))
    );
  }

  private static String selectionBody() {
    return """
      {"action":"RESULT_SELECTED","selection":{"selectionType":"IDS","entryIds":["#{entryIdOne}","#{entryIdTwo}","#{entryIdThree}"]}}
      """;
  }

  private static String resultBody() {
    return """
      {"entryIds":["#{entryIdOne}","#{entryIdTwo}","#{entryIdThree}"],"result":{"resultCode":"%s","wordingFields":[{"key":"Date","value":"#{bulkResultDate}"},{"key":"Courthouse","value":"%s"}]}}
      """.formatted(RESULT_CODE, COURTHOUSE);
  }
}
