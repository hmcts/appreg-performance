package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import utils.Headers;
import utils.WorkloadAction;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.ApplicationListFailureLogger.STATUS_SESSION_KEY;
import static utils.ApplicationListFailureLogger.logFailure;
import static utils.GatewayGetRetry.retryingGet;

/** Replays the recorded UI flow for bulk-updating Application fee details. */
public final class BulkUpdateFeesScenario {
  private BulkUpdateFeesScenario() {}

  public static ChainBuilder bulkUpdateFees() {
    return group(WorkloadAction.BULK_FEES.groupName()).on(
      exec(session -> session
        .set("bulkFeeStatusDate", LocalDate.now().minusDays(1).toString())
        .set("bulkFeePaymentReference", "PAY-"
          + String.format("%05d", ThreadLocalRandom.current().nextInt(100_000))))
        .exec(retryingGet(
          "Open application list for bulk fee update",
          http("Open application list for bulk fee update")
            .get("/application-lists/#{applicationListId}")
            .queryParam("pageNumber", "0").queryParam("pageSize", "10")
            .header("Accept", Headers.APPREG_API_MEDIA_TYPE),
          status().saveAs(STATUS_SESSION_KEY)))
        .exec(logFailure("Bulk Update Fees Status", "Open application list for bulk fee update"))
        .exec(retryingGet(
          "Get application list entries for bulk fee update",
          http("Get application list entries for bulk fee update")
            .get("/application-lists/#{applicationListId}/entries")
            .queryParam("pageNumber", "0").queryParam("pageSize", "10")
            .queryParam("sort", "sequenceNumber,asc")
            .header("Accept", Headers.APPREG_API_MEDIA_TYPE)))
        .exec(http("Preview bulk fee update")
          .post("/application-lists/#{applicationListId}/entries/bulk-action-preview")
          .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
          .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
          .body(StringBody("""
            {"action":"UPDATE_FEE_DETAILS","selection":{"selectionType":"FILTER","filter":{}}}
            """))
          .check(status().is(200)))
        .exec(http("Bulk update application fee details")
          .put("/application-lists/#{applicationListId}/entries/fees")
          .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
          .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
          .body(StringBody("""
            {"entryIds":["#{entryIdOne}","#{entryIdTwo}","#{entryIdThree}"],"feeDetails":[{"paymentStatus":"REMITTED","statusDate":"#{bulkFeeStatusDate}","paymentReference":"#{bulkFeePaymentReference}"}]}
            """))
          .check(status().in(200, 204)))
    );
  }
}
