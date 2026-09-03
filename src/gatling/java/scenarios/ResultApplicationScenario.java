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

/** Replays the recorded UI flow for applying a Result to one unresulted Application. */
public final class ResultApplicationScenario {
  private static final String RESULT_CODE = "GRSW";
  private static final String TIME_ISSUED = "08:30";

  private ResultApplicationScenario() {}

  public static ChainBuilder resultApplication() {
    return group(WorkloadAction.RESULT_APPLICATION.groupName()).on(
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
        .exec(http("Apply application result")
          .post("/application-lists/#{applicationListId}/entries/results")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
          .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
          .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
          .body(StringBody("""
            {"entryIds":["#{applicationEntryId}"],"result":{"resultCode":"%s","wordingFields":[{"key":"Time issued","value":"%s"}]}}
            """.formatted(RESULT_CODE, TIME_ISSUED)))
          .check(status().is(200))
          .check(jsonPath("$[0].entryId").isEL("#{applicationEntryId}")))
    );
  }
}
