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
import static java.util.Objects.requireNonNull;
import static utils.ApplicationListFailureLogger.STATUS_SESSION_KEY;
import static utils.ApplicationListFailureLogger.logFailure;
import static utils.GatewayGetRetry.retryingGet;

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
    return group(WorkloadAction.UPDATE_LIST.groupName()).on(
      loadApplicationList()
        .exec(updateValues())
        .exec(http("Update application list")
          .put("/application-lists/#{applicationListId}")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
          .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
          .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
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
    return exec(retryingGet(
      "Get application list details",
      http("Get application list details")
        .get("/application-lists/#{applicationListId}")
        .queryParam("pageNumber", "0")
        .queryParam("pageSize", "10")
        .header("Accept", Headers.APPREG_API_MEDIA_TYPE),
      status().saveAs(STATUS_SESSION_KEY),
      jsonPath("$.date").saveAs("applicationListDate"),
      jsonPath("$.time").saveAs("applicationListTime"),
      jsonPath("$.description").saveAs("applicationListDescription"),
      jsonPath("$.courtCode").saveAs("applicationListCourtLocationCode")))
      .exec(logFailure("Update Application List", "Get application list details"));
  }

  static String listBody(String status) {
    return """
      {"date":"#{applicationListUpdateDate}","time":"#{applicationListUpdateTime}","description":"#{applicationListUpdateDescription}","status":"%s","courtLocationCode":"#{applicationListUpdatedCourtLocationCode}","durationHours":#{applicationListDurationHours},"durationMinutes":#{applicationListDurationMinutes}}
      """.formatted(status);
  }
}
