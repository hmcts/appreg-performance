package utils;

import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Controlled read-only session-establishment profile used before the performance workload. */
public record LoginPreflightProfile(int concurrentUsers, int loginRampUpSeconds) {
  private static final String PROFILE_PREFIX = "login_preflight";

  public LoginPreflightProfile {
    if (concurrentUsers < 1 || concurrentUsers > 500) {
      throw new IllegalArgumentException("Login preflight users must be between 1 and 500");
    }
    if (loginRampUpSeconds < concurrentUsers) {
      throw new IllegalArgumentException(
          "Login preflight ramp must be at least one second per account");
    }
  }

  /** Preserves the configured average login spacing for smaller spare and retry phases. */
  public Duration rampDurationFor(int accountCount) {
    return Duration.ofMillis(Math.round(
        (double) loginRampUpSeconds * 1_000 * accountCount / concurrentUsers));
  }

  public static LoginPreflightProfile fromRuntime() {
    Properties properties = new Properties();
    Path profilePath = Path.of(System.getProperty(
        "appRegWorkloadProfileFile", "data/seed/workload/allocation-profile.properties"));
    try (var reader = Files.newBufferedReader(profilePath)) {
      properties.load(reader);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Could not read login preflight profile: " + profilePath.toAbsolutePath(), exception);
    }
    return new LoginPreflightProfile(
        positive(properties, PROFILE_PREFIX + ".concurrent_users"),
        positive(properties, PROFILE_PREFIX + ".login_ramp_up_seconds"));
  }

  private static int positive(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null) throw new IllegalArgumentException("Login preflight profile is missing " + key);
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 1) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Login preflight profile value must be a positive integer: " + key);
    }
  }
}
