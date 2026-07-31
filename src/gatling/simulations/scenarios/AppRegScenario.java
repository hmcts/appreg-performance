package scenarios;

import io.gatling.javaapi.core.ChainBuilder;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.regex;
import static io.gatling.javaapi.core.CoreDsl.substring;
import static io.gatling.javaapi.http.HttpDsl.headerRegex;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.Environment.APPLICATIONS_LIST_PATH;
import static utils.Headers.COMMON_HEADER;

public final class AppRegScenario {
  private AppRegScenario() {}

  public static ChainBuilder applicationsList(boolean sessionAuthenticated) {
    ChainBuilder applicationsList = group("AppReg_010_Applications_List").on(
      exec(http("Applications list")
        .get(APPLICATIONS_LIST_PATH)
        .headers(COMMON_HEADER)
        .check(status().is(200))
        .check(substring("HMCTS Applications Register - Home - GOV.UK"))
        .check(regex("<script src=\\\"([^\\\"]*main-[^\\\"]+\\.js)").saveAs("mainScript")))
        .exec(http("Applications list JavaScript")
          .get("/#{mainScript}")
          .check(status().is(200)))
    );

    if (!sessionAuthenticated) {
      return applicationsList;
    }

    return applicationsList.exec(http("Get application lists")
      .get("/application-lists")
      .queryParam("pageNumber", "0")
      .queryParam("pageSize", "1")
      .header("Accept", "application/vnd.hmcts.appreg.v1+json")
      .check(status().is(200))
      .check(headerRegex("Content-Type", ".*json.*")));
  }
}
