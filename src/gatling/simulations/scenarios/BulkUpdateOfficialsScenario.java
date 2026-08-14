package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import utils.Headers;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/** Replays the recorded UI flow for bulk-updating Magistrate and Court Official details. */
public final class BulkUpdateOfficialsScenario {
  private BulkUpdateOfficialsScenario() {}

  /**
   * Updates three selected entries in the Application List held in the Gatling session.
   * The caller must provide {@code applicationListId}, {@code entryIdOne}, {@code entryIdTwo}, and
   * {@code entryIdThree}; final workloads will obtain these from allocated seeded data.
   */
  public static ChainBuilder bulkUpdateOfficials() {
    return group("AppReg_065_Applications_Bulk_Officials").on(
      exec(http("Preview bulk officials update")
        .post("/application-lists/#{applicationListId}/entries/bulk-action-preview")
        .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
        .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
        .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
        .body(StringBody("""
          {"action":"UPDATE_OFFICIALS","selection":{"selectionType":"FILTER","filter":{}}}
          """))
        .check(status().is(200)))
      .exec(http("Bulk update Magistrates and Court Official")
        .post("/application-lists/#{applicationListId}/entries/officials")
        .header("Accept", "application/problem+json")
        .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
        .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
        .body(StringBody("""
          {"entryIds":["#{entryIdOne}","#{entryIdTwo}","#{entryIdThree}"],"officials":[{"type":"MAGISTRATE","title":"mr","forename":"Gatling","surname":"Magistrate One"},{"type":"MAGISTRATE","title":"mrs","forename":"Gatling","surname":"Magistrate Two"},{"type":"MAGISTRATE","title":"miss","forename":"Gatling","surname":"Magistrate Three"},{"type":"CLERK","title":"dr","forename":"Gatling","surname":"Court Official"}]}
          """))
        .check(status().is(204)))
    );
  }
}
