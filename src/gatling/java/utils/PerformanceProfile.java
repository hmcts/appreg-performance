package utils;

/**
 * Runtime configuration for the shared AppReg simulation.
 *
 * <p>The target user count is the sole load-size input. The SSO arrival rate is derived from the
 * confirmed safe AppReg/Entra login rate so each virtual user can be allocated a distinct account.
 */
public record PerformanceProfile(
    int concurrentUsers,
    int ssoRampUpSeconds,
    double successfulRequestsThreshold) {

  private static final int MAX_TEST_ACCOUNTS = 500;
  private static final int SSO_LOGINS_PER_SECOND = 10;

  public PerformanceProfile {
    if (concurrentUsers > MAX_TEST_ACCOUNTS) {
      throw new IllegalArgumentException(
          "appRegConcurrentUsers must not exceed the " + MAX_TEST_ACCOUNTS + " dedicated Test accounts");
    }
  }

  public static PerformanceProfile fromRuntime() {
    int concurrentUsers = positiveInteger(
        "appRegConcurrentUsers",
        Integer.parseInt(System.getenv().getOrDefault("PERFORMANCE_TEST_USERS", "1")));
    return new PerformanceProfile(
        concurrentUsers,
        ssoRampUpSeconds(concurrentUsers),
        percentage("appRegSuccessfulRequestsThreshold", 95.0)
    );
  }

  private static int ssoRampUpSeconds(int concurrentUsers) {
    return (int) Math.ceil((double) concurrentUsers / SSO_LOGINS_PER_SECOND);
  }

  private static int positiveInteger(String propertyName, int defaultValue) {
    int value = Integer.getInteger(propertyName, defaultValue);
    if (value <= 0) {
      throw new IllegalArgumentException(propertyName + " must be greater than zero");
    }
    return value;
  }

  private static double percentage(String propertyName, double defaultValue) {
    double value = Double.parseDouble(System.getProperty(propertyName, Double.toString(defaultValue)));
    if (value <= 0 || value > 100) {
      throw new IllegalArgumentException(propertyName + " must be greater than zero and at most 100");
    }
    return value;
  }
}
