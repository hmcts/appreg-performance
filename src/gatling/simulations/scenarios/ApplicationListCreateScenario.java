package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.LocalDate;
import java.util.UUID;
import utils.Environment;
import utils.Headers;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.Headers.COMMON_HEADER;

/**
 * Creates an application list using disposable data generated for each virtual user.
 * This chain assumes the caller has already authenticated using {@code SsoAuthentication}.
 */
public final class ApplicationListCreateScenario {
  private static final String COURT_LOCATION_CODE = System.getProperty("appRegCourtLocationCode", "B40BC00");

  private ApplicationListCreateScenario() {}

  public static ChainBuilder createApplicationList() {
    return group("AppReg_020_Application_List_Create").on(
      exec(session -> session
        .set("applicationListDate", LocalDate.now().toString())
        .set("applicationListDescription", "Gatling create-list proof " + UUID.randomUUID()))
        .exec(http("Application lists page")
          .get(Environment.APPLICATIONS_LIST_PATH)
          .headers(COMMON_HEADER)
          .check(status().is(200)))
        .exec(getCookieValue(CookieKey(Headers.XSRF_TOKEN_COOKIE).saveAs("xsrfToken")))
        .exec(http("Create application list")
          .post("/application-lists")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
          .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
          .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
          .body(StringBody("{\"date\":\"#{applicationListDate}\",\"time\":\"12:00\",\"description\":\"#{applicationListDescription}\",\"status\":\"OPEN\",\"courtLocationCode\":\"" + COURT_LOCATION_CODE + "\"}"))
          .check(status().in(200, 201))
          .check(jsonPath("$.id").saveAs("applicationListId")))
        .exec(http("Get created application list")
          .get("/application-lists/#{applicationListId}")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "10")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
          .check(status().is(200)))
    );
  }
}
