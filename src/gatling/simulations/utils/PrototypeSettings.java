package utils;

import java.time.Duration;

/** Validated runtime settings for the isolated phase-measurement prototype. */
public record PrototypeSettings(
    int users,
    int steadyStateMinutes,
    double authenticationRatePerSecond,
    int authenticationSetupTimeoutMinutes,
    double actionPaceSeconds,
    int rampDownGraceSeconds) {
  private static final int DEFAULT_USERS = 2;
  private static final int MAX_USERS = 500;
  private static final int DEFAULT_STEADY_STATE_MINUTES = 30;
  private static final double DEFAULT_AUTHENTICATION_RATE_PER_SECOND = 1.0;
  private static final int DEFAULT_AUTHENTICATION_SETUP_TIMEOUT_MINUTES = 15;
  private static final double DEFAULT_ACTION_PACE_SECONDS = 60.0;
  private static final int DEFAULT_RAMP_DOWN_GRACE_SECONDS = 60;
  private static final int MAX_DURATION_MINUTES = 24 * 60;
  private static final int MAX_INTERVAL_SECONDS = 60 * 60;

  public PrototypeSettings {
    if (users < 1 || users > MAX_USERS) {
      throw new IllegalArgumentException("Prototype users must be between 1 and " + MAX_USERS);
    }
    requireMinutes("Prototype steady-state duration", steadyStateMinutes);
    requirePositiveFinite("Prototype authentication rate", authenticationRatePerSecond);
    requireMinutes("Prototype authentication setup timeout", authenticationSetupTimeoutMinutes);
    requirePositiveFinite("Prototype action pace", actionPaceSeconds);
    if (actionPaceSeconds > MAX_INTERVAL_SECONDS) {
      throw new IllegalArgumentException(
          "Prototype action pace must not exceed " + MAX_INTERVAL_SECONDS + " seconds");
    }
    if (rampDownGraceSeconds < 0 || rampDownGraceSeconds > MAX_INTERVAL_SECONDS) {
      throw new IllegalArgumentException(
          "Prototype ramp-down grace must be between 0 and " + MAX_INTERVAL_SECONDS + " seconds");
    }
    if (authenticationRampDuration(users, authenticationRatePerSecond)
        .compareTo(Duration.ofMinutes(authenticationSetupTimeoutMinutes)) > 0) {
      throw new IllegalArgumentException(
          "Prototype authentication setup timeout must cover the configured authentication ramp");
    }
  }

  public static PrototypeSettings fromRuntime() {
    return new PrototypeSettings(
        integerProperty("appRegPrototypeUsers", DEFAULT_USERS),
        integerProperty("appRegPrototypeSteadyStateMinutes", DEFAULT_STEADY_STATE_MINUTES),
        doubleProperty(
            "appRegPrototypeAuthenticationRatePerSecond",
            DEFAULT_AUTHENTICATION_RATE_PER_SECOND),
        integerProperty(
            "appRegPrototypeAuthenticationSetupTimeoutMinutes",
            DEFAULT_AUTHENTICATION_SETUP_TIMEOUT_MINUTES),
        doubleProperty("appRegPrototypeActionPaceSeconds", DEFAULT_ACTION_PACE_SECONDS),
        integerProperty("appRegPrototypeRampDownGraceSeconds", DEFAULT_RAMP_DOWN_GRACE_SECONDS));
  }

  public Duration authenticationRampDuration() {
    return authenticationRampDuration(users, authenticationRatePerSecond);
  }

  public Duration authenticationSetupTimeout() {
    return Duration.ofMinutes(authenticationSetupTimeoutMinutes);
  }

  public Duration steadyStateDuration() {
    return Duration.ofMinutes(steadyStateMinutes);
  }

  public Duration actionPace() {
    return durationFromSeconds(actionPaceSeconds);
  }

  public Duration maximumSimulationDuration() {
    return authenticationSetupTimeout()
        .plus(steadyStateDuration())
        .plusSeconds(rampDownGraceSeconds);
  }

  /** Small dependency-free check for the configuration defaults and validation boundaries. */
  public static void main(String[] args) {
    var defaults = new PrototypeSettings(
        DEFAULT_USERS,
        DEFAULT_STEADY_STATE_MINUTES,
        DEFAULT_AUTHENTICATION_RATE_PER_SECOND,
        DEFAULT_AUTHENTICATION_SETUP_TIMEOUT_MINUTES,
        DEFAULT_ACTION_PACE_SECONDS,
        DEFAULT_RAMP_DOWN_GRACE_SECONDS);
    if (defaults.users() != 2
        || defaults.steadyStateMinutes() != 30
        || defaults.authenticationRatePerSecond() != 1.0
        || defaults.authenticationSetupTimeoutMinutes() != 15
        || defaults.actionPaceSeconds() != 60.0
        || defaults.rampDownGraceSeconds() != 60
        || !defaults.authenticationRampDuration().equals(Duration.ofSeconds(2))) {
      throw new IllegalStateException("Prototype defaults changed unexpectedly");
    }
    expectInvalid(() -> new PrototypeSettings(0, 30, 1, 15, 60, 60));
    expectInvalid(() -> new PrototypeSettings(MAX_USERS + 1, 30, 1, 15, 60, 60));
    expectInvalid(() -> new PrototypeSettings(2, 0, 1, 15, 60, 60));
    expectInvalid(() -> new PrototypeSettings(2, 30, 0, 15, 60, 60));
    expectInvalid(() -> new PrototypeSettings(2, 30, 1, 0, 60, 60));
    expectInvalid(() -> new PrototypeSettings(2, 30, 1, 15, 0, 60));
    expectInvalid(() -> new PrototypeSettings(2, 30, 1, 15, 60, -1));
    expectInvalid(() -> new PrototypeSettings(500, 30, 0.1, 15, 60, 60));
    PhaseController.selfCheck();
    System.out.println("Prototype settings self-check passed");
  }

  private static Duration authenticationRampDuration(int users, double ratePerSecond) {
    return durationFromSeconds(users / ratePerSecond);
  }

  private static Duration durationFromSeconds(double seconds) {
    return Duration.ofMillis(Math.max(1L, (long) Math.ceil(seconds * 1_000.0)));
  }

  private static void requireMinutes(String name, int value) {
    if (value < 1 || value > MAX_DURATION_MINUTES) {
      throw new IllegalArgumentException(name + " must be between 1 and " + MAX_DURATION_MINUTES + " minutes");
    }
  }

  private static void requirePositiveFinite(String name, double value) {
    if (!Double.isFinite(value) || value <= 0) {
      throw new IllegalArgumentException(name + " must be a positive finite number");
    }
  }

  private static int integerProperty(String name, int defaultValue) {
    String value = System.getProperty(name);
    if (value == null) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(name + " must be an integer", exception);
    }
  }

  private static double doubleProperty(String name, double defaultValue) {
    String value = System.getProperty(name);
    if (value == null) return defaultValue;
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(name + " must be a number", exception);
    }
  }

  private static void expectInvalid(Runnable action) {
    try {
      action.run();
      throw new IllegalStateException("Expected invalid prototype settings to be rejected");
    } catch (IllegalArgumentException expected) {
      // Intentionally empty: each invalid boundary must reject construction.
    }
  }
}
