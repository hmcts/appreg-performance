package scenarios;

import io.gatling.javaapi.core.ChainBuilder;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.http.HttpDsl.headerRegex;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.Headers.COMMON_HEADER;

/**
 * Searches Application Lists by description.
 * This chain assumes the caller has already authenticated using {@code SsoAuthentication}.
 */
public final class SearchScenario {
  private static final String DEFAULT_APPLICATION_LIST_DESCRIPTION = "Bulk";
  private static final String APPLICATION_LIST_DESCRIPTION = System.getProperty(
    "appRegApplicationListSearchDescription",
    DEFAULT_APPLICATION_LIST_DESCRIPTION
  );
  private static final int FIRST_PAGE = 0;
  private static final int PAGE_SIZE = 10;
  private static final String SORT_ORDER = "date,desc";

  private SearchScenario() {}

  public static ChainBuilder searchApplicationLists() {
    return group("AppReg_030_Application_List_Search").on(
      exec(http("Application lists page")
        .get("/applications-list")
        .headers(COMMON_HEADER)
        .check(status().is(200)))
        .exec(http("Search application lists by description")
          .get("/application-lists")
          .queryParam("description", APPLICATION_LIST_DESCRIPTION)
          .queryParam("pageNumber", FIRST_PAGE)
          .queryParam("pageSize", PAGE_SIZE)
          .queryParam("sort", SORT_ORDER)
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200))
          .check(headerRegex("Content-Type", ".*json.*")))
    );
  }
}
