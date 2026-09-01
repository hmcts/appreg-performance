package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import utils.Headers;
import utils.WorkloadAction;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static java.util.Objects.requireNonNull;

/** Replays the recorded UI flow for closing a close-ready Application List. */
public final class CloseApplicationListScenario {
  private CloseApplicationListScenario() {}

  public static ChainBuilder closeApplicationList() {
    return group(WorkloadAction.CLOSE_LIST.groupName()).on(
      UpdateApplicationListScenario.loadApplicationList()
        .exec(UpdateApplicationListScenario.updateValues())
        .exec(http("Close application list")
          .put("/application-lists/#{applicationListId}")
          .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
          .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
          .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
          .body(StringBody(requireNonNull(UpdateApplicationListScenario.listBody("CLOSED"))))
          .check(status().is(200)))
    );
  }
}
