package utils;

import io.gatling.http.response.Response;
import io.gatling.javaapi.core.Session;
import java.util.Set;
import java.util.function.BiFunction;

/** Minimal sanitized diagnostics for unexpected HTTP statuses during Gatling runs. */
public final class DiagnosticLogging {
  private DiagnosticLogging() {}

  public static BiFunction<Response, Session, Response> logIfStatusAtLeast(
      String requestName, int minimumStatus) {
    return (response, session) -> {
      int status = response.status().code();
      if (status >= minimumStatus) log(requestName, response, session);
      return response;
    };
  }

  public static BiFunction<Response, Session, Response> logIfStatusNotIn(
      String requestName, Set<Integer> expectedStatuses) {
    return (response, session) -> {
      int status = response.status().code();
      if (!expectedStatuses.contains(status)) log(requestName, response, session);
      return response;
    };
  }

  private static void log(String requestName, Response response, Session session) {
    int status = response.status().code();
    System.err.printf(
        "APPREG_HTTP_DIAGNOSTIC request=%s status=%d retryAfter=%s user=%s accountOffset=%s authState=%s%n",
        requestName,
        status,
        status == 429 ? response.headers().get("Retry-After") : "-",
        sessionValue(session, "username"),
        sessionValue(session, "accountOffset"),
        authState(session));
  }

  private static String authState(Session session) {
    return "sessionId=" + session.contains("entraSessionId")
        + ",context=" + session.contains("entraContext")
        + ",flowToken=" + session.contains("entraFlowToken")
        + ",canary=" + session.contains("entraCanary")
        + ",xsrfToken=" + session.contains("xsrfToken")
        + ",applicationListId=" + session.contains("applicationListId");
  }

  private static String sessionValue(Session session, String key) {
    Object value = session.get(key);
    return value == null ? "-" : value.toString();
  }
}
