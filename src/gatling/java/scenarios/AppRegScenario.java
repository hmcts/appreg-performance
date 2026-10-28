package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import utils.Headers;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.regex;
import static io.gatling.javaapi.core.CoreDsl.substring;
import static io.gatling.javaapi.http.HttpDsl.headerRegex;
import static io.gatling.javaapi.http.HttpDsl.http;
import static utils.GatewayGetRetry.retryingGet;
import static utils.Environment.APPLICATIONS_LIST_PATH;
import static utils.Headers.COMMON_HEADER;

public final class AppRegScenario {
  private AppRegScenario() {}

  public static ChainBuilder applicationsList(boolean sessionAuthenticated) {
    ChainBuilder applicationsList = group("AppReg_010_Applications_List").on(
      exec(retryingGet("Applications list", http("Applications list")
        .get(APPLICATIONS_LIST_PATH)
        .headers(COMMON_HEADER),
        substring("HMCTS Applications Register - Home - GOV.UK"),
        regex("<script src=\\\"([^\\\"]*main-[^\\\"]+\\.js)").saveAs("mainScript")))
        .exec(retryingGet("Applications list JavaScript", http("Applications list JavaScript")
          .get("/#{mainScript}")))
    );

    if (!sessionAuthenticated) {
      return applicationsList;
    }

    return applicationsList.exec(retryingGet("Get application lists", http("Get application lists")
      .get("/application-lists")
      .queryParam("pageNumber", "0")
      .queryParam("pageSize", "1")
      .header("Accept", Headers.APPREG_API_MEDIA_TYPE),
      headerRegex("Content-Type", ".*json.*")));
  }
}
