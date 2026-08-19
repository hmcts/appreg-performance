package utils;

import io.gatling.javaapi.core.Session;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the dedicated accounts whose initial read-only preflight did not complete. The queue is
 * written only after the initial Gatling run has ended, so its retry is deliberately a separate,
 * trailing phase rather than an immediate retry that could increase the login rate. This is a
 * workload-readiness measure, not an SSO performance-test retry policy.
 */
public final class LoginPreflightRetryQueue {
  public static final Path PATH = Path.of("build", "login-preflight-retry.csv");
  private static final Map<String, Object> FAILED_ACCOUNTS = new LinkedHashMap<>();

  private LoginPreflightRetryQueue() {}

  public static Session retainFailedAccount(Session session) {
    if (session.isFailed()) {
      String username = session.getString("username");
      Object accountOffset = session.get("accountOffset");
      if (username != null && accountOffset != null) {
        synchronized (FAILED_ACCOUNTS) {
          FAILED_ACCOUNTS.putIfAbsent(username, accountOffset);
        }
      }
      // The initial pass is reported, but its HTTP failures must not prevent the trailing retry.
      return session.markAsSucceeded();
    }
    return session;
  }

  public static void write() {
    try {
      Files.createDirectories(PATH.getParent());
      try (var writer = Files.newBufferedWriter(PATH)) {
        writer.write("username,accountOffset\n");
        synchronized (FAILED_ACCOUNTS) {
          for (var account : FAILED_ACCOUNTS.entrySet()) {
            writer.write(account.getKey());
            writer.write(',');
            writer.write(account.getValue().toString());
            writer.write('\n');
          }
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Could not write login preflight retry queue", exception);
    }
  }
}
