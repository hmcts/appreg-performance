package utils;

import io.gatling.http.response.Response;
import io.gatling.javaapi.core.Session;
import java.util.Set;
import java.util.function.BiFunction;

/** Emits a compact, failure-only HTTP diagnostic without exposing response content or secrets. */
public final class DiagnosticLogging {
  private DiagnosticLogging() {}

  public static BiFunction<Response, Session, Response> logIfStatusAtLeast(String requestName, int minimumStatus) {
    return (response, session) -> {
      if (response.status().code() >= minimumStatus) {
        log(requestName, response, session);
      }
      return response;
    };
  }

  public static BiFunction<Response, Session, Response> logIfStatusNotIn(String requestName, Set<Integer> acceptedStatuses) {
    return (response, session) -> {
      if (!acceptedStatuses.contains(response.status().code())) {
        log(requestName, response, session);
      }
      return response;
    };
  }

  private static void log(String requestName, Response response, Session session) {
    String retryAfter = response.status().code() == 429 ? response.headers().get("Retry-After") : "-";
    String user = sessionValue(session, "username");
    String accountOffset = sessionValue(session, "accountOffset");

    System.out.printf(
      "APPREG_HTTP_DIAGNOSTIC request=%s status=%d retryAfter=%s user=%s accountOffset=%s%n",
      requestName,
      response.status().code(),
      retryAfter == null ? "-" : retryAfter,
      user,
      accountOffset
    );
  }

  private static String sessionValue(Session session, String key) {
    Object value = session.get(key);
    return value == null ? "-" : value.toString();
  }
}
