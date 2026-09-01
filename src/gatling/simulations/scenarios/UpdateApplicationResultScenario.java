package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.LocalDate;
import utils.Headers;
import utils.WorkloadAction;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.GatewayGetRetry.retryingGet;

/** Replays the recorded UI flow for updating a Result on one Application. */
public final class UpdateApplicationResultScenario {
  private static final String RESULT_CODE = System.getProperty("appRegUpdateResultCode", "COST");
  private static final String WORDING_VALUE = System.getProperty("appRegUpdateResultWording", "501 Pounds");

  private UpdateApplicationResultScenario() {}

  public static ChainBuilder updateApplicationResult() {
    return group(WorkloadAction.UPDATE_RESULT.groupName()).on(
      exec(session -> session.set("resultCodeDate", LocalDate.now().toString()))
        .exec(http("Preview selected application result")
          .post("/application-lists/#{applicationListId}/entries/bulk-action-preview")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
          .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
          .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
          .body(StringBody("""
            {"action":"RESULT_SELECTED","selection":{"selectionType":"IDS","entryIds":["#{applicationEntryId}"]}}
            """))
          .check(status().is(200)))
        .exec(retryingGet(
          "Get result codes",
          http("Get result codes")
            .get("/result-codes")
            .queryParam("pageNumber", "0")
            .queryParam("pageSize", "100")
            .header("Accept", Headers.APPREG_API_MEDIA_TYPE)))
        .exec(retryingGet(
          "Get selected result-code details",
          http("Get selected result-code details")
            .get("/result-codes/" + RESULT_CODE)
            .queryParam("date", "#{resultCodeDate}")
            .header("Accept", Headers.APPREG_API_MEDIA_TYPE)))
        .exec(http("Update application result")
          .post("/application-lists/#{applicationListId}/entries/results")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
          .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
          .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
          .body(StringBody("""
            {"entryIds":["#{applicationEntryId}"],"result":{"resultCode":"%s","wordingFields":[{"key":"Amount of costs","value":"%s"}]}}
            """.formatted(RESULT_CODE, WORDING_VALUE)))
          .check(status().is(200))
          .check(jsonPath("$[0].entryId").isEL("#{applicationEntryId}")))
    );
  }
}
