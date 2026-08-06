package scenarios;

import io.gatling.javaapi.core.ChainBuilder;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/** Replays the recorded UI flow for closing a close-ready Application List. */
public final class CloseApplicationListScenario {
  private static final int DURATION_HOURS = Integer.getInteger("appRegApplicationListDurationHours", 10);

  private CloseApplicationListScenario() {}

  public static ChainBuilder closeApplicationList() {
    return group("AppReg_090_Application_List_Close").on(
      UpdateApplicationListScenario.loadApplicationList()
        .exec(session -> session.set("applicationListDurationHours", DURATION_HOURS))
        .exec(http("Close application list")
          .put("/application-lists/#{applicationListId}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody(UpdateApplicationListScenario.listBody("CLOSED")))
          .check(status().is(200)))
    );
  }
}
