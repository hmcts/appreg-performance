package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.LocalDate;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static java.util.Objects.requireNonNull;

/** Replays the recorded UI flow for a simple update to an open Application List. */
public final class UpdateApplicationListScenario {
  private static final int DURATION_HOURS = Integer.getInteger("appRegApplicationListDurationHours", 10);
  private static final int DURATION_MINUTES = Integer.getInteger("appRegApplicationListDurationMinutes", 11);
  private static final String UPDATE_TIME = System.getProperty("appRegApplicationListUpdateTime", "11:01");
  private static final String UPDATE_DESCRIPTION = System.getProperty(
    "appRegApplicationListUpdateDescription", "Gatling application list update proof");
  private static final String UPDATE_COURT_LOCATION_CODE = System.getProperty("appRegUpdatedCourtLocationCode");

  private UpdateApplicationListScenario() {}

  public static ChainBuilder updateApplicationList() {
    return group("AppReg_080_Application_List_Update").on(
      loadApplicationList()
        .exec(updateValues())
        .exec(http("Update application list")
          .put("/application-lists/#{applicationListId}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody(requireNonNull(listBody("OPEN"))))
          .check(status().is(200)))
    );
  }

  static ChainBuilder updateValues() {
    return exec(session -> session
      .set("applicationListUpdateDate", LocalDate.now().plusDays(1).toString())
      .set("applicationListUpdateTime", UPDATE_TIME)
      .set("applicationListUpdateDescription", UPDATE_DESCRIPTION)
      .set("applicationListUpdatedCourtLocationCode", UPDATE_COURT_LOCATION_CODE == null
        ? session.getString("applicationListCourtLocationCode")
        : UPDATE_COURT_LOCATION_CODE)
      .set("applicationListDurationHours", DURATION_HOURS)
      .set("applicationListDurationMinutes", DURATION_MINUTES));
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
      {"date":"#{applicationListUpdateDate}","time":"#{applicationListUpdateTime}","description":"#{applicationListUpdateDescription}","status":"%s","courtLocationCode":"#{applicationListUpdatedCourtLocationCode}","durationHours":#{applicationListDurationHours},"durationMinutes":#{applicationListDurationMinutes}}
      """.formatted(status);
  }
}
