package utils;

import io.gatling.http.response.Response;
import io.gatling.javaapi.core.Session;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.function.BiFunction;

/** Minimal sanitized diagnostics for unexpected HTTP statuses during Gatling runs. */
public final class DiagnosticLogging {
  private static final boolean SSO_HTML_DIAGNOSTICS_ENABLED = isEnabled("APPREG_SSO_DIAGNOSTICS");
  private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
  private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\"sErrorCode\":\"([^\"]+)\"");
  private static final Pattern POST_TYPE_PATTERN = Pattern.compile("\"sPOST_Type\":(\\d+)");
  private static final Pattern KMSI_ENABLED_PATTERN = Pattern.compile("\"fKMSIEnabled\":(true|false)");

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

  /**
   * Summarizes the shape of Entra's HTML continuation page without logging body content, tokens,
   * or URLs. This is only meant to explain why the SSO flow did not continue via redirects.
   */
  public static BiFunction<Response, Session, Response> logIfHtmlContinuationPage(String requestName) {
    return (response, session) -> {
      if (!SSO_HTML_DIAGNOSTICS_ENABLED) return response;
      int status = response.status().code();
      String contentType = response.headers().get("Content-Type");
      if (status == 200 && contentType != null && contentType.toLowerCase().contains("text/html")) {
        logHtmlContinuation(requestName, response, session);
      }
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

  private static void logHtmlContinuation(String requestName, Response response, Session session) {
    String body = response.body().string();
    System.err.printf(
        "APPREG_HTTP_DIAGNOSTIC request=%s status=%d continuationPage=true hasLocation=%s "
            + "title=%s hasUrlPost=%s hasUrlLogin=%s hasUrlResume=%s hasUrlRefresh=%s "
            + "hasUrlCancel=%s hasAppRegCallback=%s kmsiEnabled=%s postType=%s errorCode=%s "
            + "user=%s accountOffset=%s authState=%s%n",
        requestName,
        response.status().code(),
        response.headers().contains("Location"),
        shortValue(extract(body, TITLE_PATTERN)),
        body.contains("\"urlPost\":"),
        body.contains("\"urlLogin\":"),
        body.contains("\"urlResume\":"),
        body.contains("\"urlRefresh\":"),
        body.contains("\"urlCancel\":"),
        body.contains("/sso/login-callback"),
        shortValue(extract(body, KMSI_ENABLED_PATTERN)),
        shortValue(extract(body, POST_TYPE_PATTERN)),
        shortValue(extract(body, ERROR_CODE_PATTERN)),
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

  private static String extract(String body, Pattern pattern) {
    Matcher matcher = pattern.matcher(body);
    return matcher.find() ? matcher.group(1).replaceAll("\\s+", " ").trim() : "-";
  }

  private static String shortValue(String value) {
    return value.length() > 80 ? value.substring(0, 80) : value;
  }

  private static boolean isEnabled(String environmentVariableName) {
    String value = System.getenv(environmentVariableName);
    if (value == null) return false;
    return switch (value.trim().toLowerCase()) {
      case "1", "true", "yes", "on" -> true;
      default -> false;
    };
  }
}