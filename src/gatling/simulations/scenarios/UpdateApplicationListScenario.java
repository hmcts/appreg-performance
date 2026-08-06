package scenarios;

import io.gatling.javaapi.core.ChainBuilder;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/** Replays the recorded UI flow for a simple update to an open Application List. */
public final class UpdateApplicationListScenario {
  private static final int DURATION_HOURS = Integer.getInteger("appRegApplicationListDurationHours", 10);

  private UpdateApplicationListScenario() {}

  public static ChainBuilder updateApplicationList() {
    return group("AppReg_080_Application_List_Update").on(
      loadApplicationList()
        .exec(session -> session.set("applicationListDurationHours", DURATION_HOURS))
        .exec(http("Update application list")
          .put("/application-lists/#{applicationListId}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody(listBody("OPEN")))
          .check(status().is(200)))
    );
  }

  static ChainBuilder loadApplicationList() {
    return exec(http("Get application list details")
      .get("/application-lists/#{applicationListId}")
      .queryParam("pageNumber", "0")
      .queryParam("pageSize", "10")
      .header("Accept", "application/vnd.hmcts.appreg.v1+json")
      .check(status().is(200))
      .check(jsonPath("$.date").saveAs("applicationListDate"))
      .check(jsonPath("$.time").saveAs("applicationListTime"))
      .check(jsonPath("$.description").saveAs("applicationListDescription"))
      .check(jsonPath("$.courtCode").saveAs("applicationListCourtLocationCode")));
  }

  static String listBody(String status) {
    return """
      {"date":"#{applicationListDate}","time":"#{applicationListTime}","description":"#{applicationListDescription}","status":"%s","courtLocationCode":"#{applicationListCourtLocationCode}","durationHours":#{applicationListDurationHours},"durationMinutes":0}
      """.formatted(status);
  }
}
