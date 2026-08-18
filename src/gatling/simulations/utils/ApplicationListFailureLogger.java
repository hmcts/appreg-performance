package utils;

import io.gatling.javaapi.core.ChainBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.gatling.javaapi.core.CoreDsl.exec;

/** Emits safe correlation details for failed Application List GET requests. */
public final class ApplicationListFailureLogger {
  public static final String STATUS_SESSION_KEY = "applicationListGetStatus";
  private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationListFailureLogger.class);

  private ApplicationListFailureLogger() {}

  public static ChainBuilder logFailure(String action, String requestName) {
    return exec(session -> {
      Integer statusCode = session.getInt(STATUS_SESSION_KEY);
      if (statusCode != null && statusCode != 200) {
        LOGGER.warn(
            "Application List GET failure: action={}, request={}, status={}, applicationListId={}",
            action, requestName, statusCode, session.getString("applicationListId"));
      }
      return session;
    });
  }
}
