package utils;

import io.gatling.javaapi.core.Session;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates the bounded preflight recovery phases. Failed primary accounts first consume spare
 * accounts; only a shortfall after that phase returns to the failed primary accounts for one retry.
 */
public final class LoginPreflightRetryQueue {
  public static final Path PRIMARY_FAILURES_PATH = Path.of("build", "login-preflight-primary-failures.csv");
  public static final Path RETRY_PATH = Path.of("build", "login-preflight-retry.csv");
  private static final Map<String, Integer> PRIMARY_FAILURES = new LinkedHashMap<>();
  private static final Map<String, Integer> RETRY_FAILURES = new LinkedHashMap<>();

  public record Account(String username, int accountOffset) {}

  private LoginPreflightRetryQueue() {}

  public static Session retainFailedAccount(Session session) {
    if (session.isFailed()) {
      String username = session.getString("username");
      Object accountOffset = session.get("accountOffset");
      if (username != null && accountOffset != null) {
        synchronized (PRIMARY_FAILURES) {
          PRIMARY_FAILURES.putIfAbsent(username, ((Number) accountOffset).intValue());
        }
      }
      // The initial pass is reported, but its HTTP failures must not prevent the trailing retry.
      return session.markAsSucceeded();
    }
    return session;
  }

  /** Records a failed spare as the corresponding failed primary account to retry once. */
  public static Session retainFailedSpare(Session session) {
    if (session.isFailed()) {
      String username = session.getString("retryUsername");
      Object accountOffset = session.get("accountOffset");
      if (username != null && accountOffset != null) {
        synchronized (RETRY_FAILURES) {
          RETRY_FAILURES.putIfAbsent(username, ((Number) accountOffset).intValue());
        }
      }
      return session.markAsSucceeded();
    }
    return session;
  }

  public static void writePrimaryFailures() {
    write(PRIMARY_FAILURES_PATH, PRIMARY_FAILURES);
  }

  public static void writeRetryFailures() {
    write(RETRY_PATH, RETRY_FAILURES);
  }

  public static List<Account> read(Path path) {
    try {
      List<Account> accounts = new ArrayList<>();
      for (String line : Files.readAllLines(path)) {
        if (line.equals("username,accountOffset")) continue;
        String[] columns = line.split(",", -1);
        if (columns.length == 2) accounts.add(new Account(columns[0], Integer.parseInt(columns[1])));
      }
      return accounts;
    } catch (IOException exception) {
      throw new IllegalArgumentException("Could not read login preflight queue: " + path.toAbsolutePath(), exception);
    }
  }

  private static void write(Path path, Map<String, Integer> accounts) {
    try {
      Files.createDirectories(path.getParent());
      try (var writer = Files.newBufferedWriter(path)) {
        writer.write("username,accountOffset\n");
        synchronized (accounts) {
          for (var account : accounts.entrySet()) {
            writer.write(account.getKey());
            writer.write(',');
            writer.write(account.getValue().toString());
            writer.write('\n');
          }
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Could not write login preflight queue", exception);
    }
  }
}
