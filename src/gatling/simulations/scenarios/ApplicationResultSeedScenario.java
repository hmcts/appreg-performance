package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import utils.Headers;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/** Creates an existing Result on the Application Entry in the session for proof-only setup. */
public final class ApplicationResultSeedScenario {
  private static final String INITIAL_RESULT_CODE = "AUTH";

  private ApplicationResultSeedScenario() {}

  public static ChainBuilder createExistingResult() {
    return group("AppReg_055_Application_Result_Seed").on(
      exec(http("Create proof setup result")
        .post("/application-lists/#{applicationListId}/entries/results")
        .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
        .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
        .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
        .body(StringBody("""
          {"entryIds":["#{applicationEntryId}"],"result":{"resultCode":"%s","wordingFields":[]}}
          """.formatted(INITIAL_RESULT_CODE)))
        .check(status().is(200))
        .check(jsonPath("$[0].entryId").isEL("#{applicationEntryId}")))
    );
  }
}
