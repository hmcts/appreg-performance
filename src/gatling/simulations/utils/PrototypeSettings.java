package utils;

/** Validated runtime settings for the isolated phase-measurement prototype. */
public record PrototypeSettings(int users, int steadyStateMinutes) {
  private static final int DEFAULT_USERS = 2;
  private static final int MAX_USERS = 500;
  private static final int DEFAULT_STEADY_STATE_MINUTES = 30;

  public PrototypeSettings {
    if (users < 1 || users > MAX_USERS) {
      throw new IllegalArgumentException("Prototype users must be between 1 and " + MAX_USERS);
    }
    if (steadyStateMinutes < 1) {
      throw new IllegalArgumentException("Prototype steady-state duration must be positive");
    }
  }

  public static PrototypeSettings fromRuntime() {
    return new PrototypeSettings(
        integerProperty("appRegPrototypeUsers", DEFAULT_USERS),
        integerProperty("appRegPrototypeSteadyStateMinutes", DEFAULT_STEADY_STATE_MINUTES));
  }

  /** Small dependency-free check for the configuration defaults and validation boundaries. */
  public static void main(String[] args) {
    var defaults = new PrototypeSettings(DEFAULT_USERS, DEFAULT_STEADY_STATE_MINUTES);
    if (defaults.users() != 2 || defaults.steadyStateMinutes() != 30) {
      throw new IllegalStateException("Prototype defaults changed unexpectedly");
    }
    expectInvalid(() -> new PrototypeSettings(0, 30));
    expectInvalid(() -> new PrototypeSettings(MAX_USERS + 1, 30));
    expectInvalid(() -> new PrototypeSettings(2, 0));
    System.out.println("Prototype settings self-check passed");
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

  private static void expectInvalid(Runnable action) {
    try {
      action.run();
      throw new IllegalStateException("Expected invalid prototype settings to be rejected");
    } catch (IllegalArgumentException expected) {
      // Intentionally empty: each invalid boundary must reject construction.
    }
  }
}
