package utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Runtime configuration and isolated deterministic plans for ramp-up and measured workload actions. */
public record WorkloadProfile(
    String name,
    int concurrentUsers,
    int durationMinutes,
    int actionsPerUser,
    int loginRampUpSeconds,
    int authenticationSetupTimeoutMinutes,
    double actionPaceSeconds,
    int rampDownGraceSeconds,
    Map<WorkloadAction, Integer> scheduledActionCounts,
    List<WorkloadAction> actionPlan,
    int rampActionsPerUser,
    Map<WorkloadAction, Integer> rampScheduledActionCounts,
    List<WorkloadAction> rampActionPlan) {

  private static final int MAX_TEST_ACCOUNTS = 500;
  private static final int MAX_DURATION_MINUTES = 1_440;
  private static final double LOGIN_INTERVAL_SECONDS = 1.0;
  private static final double MINIMUM_ACTION_PACE_SECONDS = 60.0;
  private static final double MAXIMUM_ACTION_PACE_SECONDS = 3_600.0;
  private static final int DEFAULT_AUTHENTICATION_SETUP_TIMEOUT_MINUTES = 15;
  private static final double DEFAULT_ACTION_PACE_SECONDS = 60.0;
  private static final int DEFAULT_RAMP_DOWN_GRACE_SECONDS = 60;
  private static final String MAX_USERS_PROPERTY = "appRegMaxUsers";
  private static final String DURATION_MINUTES_PROPERTY = "appRegDurationMinutes";
  private static final String SETUP_TIMEOUT_MINUTES_PROPERTY =
      "appRegWorkloadAuthenticationSetupTimeoutMinutes";
  private static final String ACTION_PACE_SECONDS_PROPERTY = "appRegWorkloadActionPaceSeconds";
  private static final String RAMP_DOWN_GRACE_SECONDS_PROPERTY =
      "appRegWorkloadRampDownGraceSeconds";

  public WorkloadProfile {
    if ("smoke".equals(name)) {
      throw new IllegalArgumentException("appRegWorkloadProfile=smoke is for one-user proofs, not a workload run");
    }
    if (concurrentUsers < 1 || concurrentUsers > MAX_TEST_ACCOUNTS) {
      throw new IllegalArgumentException("Workload concurrent users must be between 1 and " + MAX_TEST_ACCOUNTS);
    }
    requireMinutes("Workload duration", durationMinutes);
    requireMinutes("Workload authentication setup timeout", authenticationSetupTimeoutMinutes);
    requireActionPace(actionPaceSeconds);
    if (rampDownGraceSeconds < 0 || rampDownGraceSeconds > 3_600) {
      throw new IllegalArgumentException("Workload ramp-down grace must be between 0 and 3600 seconds");
    }
    if (actionsPerUser != durationMinutes) {
      throw new IllegalArgumentException("Workload currently reserves one measured action per user per minute");
    }
    if (loginRampUpSeconds < minimumLoginRampUpSeconds(concurrentUsers)) {
      throw new IllegalArgumentException(
          "Workload login ramp must allow one login every " + LOGIN_INTERVAL_SECONDS + " seconds");
    }
    if (loginRampUpSeconds > authenticationSetupTimeoutMinutes * 60L) {
      throw new IllegalArgumentException("Workload authentication setup timeout must cover the login injection ramp");
    }
    int expectedRampActionsPerUser = maximumActionsPerUser(
        authenticationSetupTimeoutMinutes, actionPaceSeconds);
    if (rampActionsPerUser != expectedRampActionsPerUser) {
      throw new IllegalArgumentException("Workload ramp action capacity does not match the setup deadline and pace");
    }
    scheduledActionCounts = Map.copyOf(scheduledActionCounts);
    actionPlan = List.copyOf(actionPlan);
    rampScheduledActionCounts = Map.copyOf(rampScheduledActionCounts);
    rampActionPlan = List.copyOf(rampActionPlan);
    requirePlan(
        "measured",
        scheduledActionCounts,
        actionPlan,
        Math.multiplyExact(concurrentUsers, actionsPerUser));
    requirePlan(
        "ramp-up",
        rampScheduledActionCounts,
        rampActionPlan,
        Math.multiplyExact(concurrentUsers, rampActionsPerUser));
  }

  public static WorkloadProfile fromRuntime() {
    String name = System.getProperty(
        "appRegWorkloadProfile", System.getenv().getOrDefault("WORKLOAD_PROFILE", "smoke"));
    if ("smoke".equals(name)) {
      throw new IllegalArgumentException("appRegWorkloadProfile=smoke is for one-user proofs, not a workload run");
    }
    Properties properties = loadProperties();
    int configuredUsers = positive(properties, name + ".concurrent_users");
    int concurrentUsers = cappedUsers(configuredUsers);
    int configuredDurationMinutes = positive(properties, name + ".duration_minutes");
    int durationMinutes = cappedDuration(configuredDurationMinutes);
    int actionsPerUser = durationMinutes;
    int configuredLoginRampUpSeconds = positive(properties, name + ".login_ramp_up_seconds");
    int loginRampUpSeconds = Math.min(
        configuredLoginRampUpSeconds, minimumLoginRampUpSeconds(concurrentUsers));
    int setupTimeoutMinutes = integerProperty(
        SETUP_TIMEOUT_MINUTES_PROPERTY, DEFAULT_AUTHENTICATION_SETUP_TIMEOUT_MINUTES);
    double actionPaceSeconds = doubleProperty(ACTION_PACE_SECONDS_PROPERTY, DEFAULT_ACTION_PACE_SECONDS);
    int rampDownGraceSeconds = integerProperty(
        RAMP_DOWN_GRACE_SECONDS_PROPERTY, DEFAULT_RAMP_DOWN_GRACE_SECONDS);
    Map<WorkloadAction, Integer> configuredCounts = scheduledCounts(properties, name);
    int configuredActionCount = Math.multiplyExact(configuredUsers, configuredDurationMinutes);
    int measuredActionCount = Math.multiplyExact(concurrentUsers, actionsPerUser);
    Map<WorkloadAction, Integer> measuredCounts = scaledScheduledCounts(
        configuredCounts, configuredActionCount, measuredActionCount);
    // Ramp-up users start work as soon as they authenticate. Reserve enough data for the
    // conservative case where every user could work for the whole setup window; unused rows are
    // harmless and are kept separate from the rows reserved for measurement.
    int rampActionsPerUser = maximumActionsPerUser(setupTimeoutMinutes, actionPaceSeconds);
    int maximumRampActionCount = Math.multiplyExact(concurrentUsers, rampActionsPerUser);
    Map<WorkloadAction, Integer> rampCounts = scaledScheduledCounts(
        configuredCounts, configuredActionCount, maximumRampActionCount);
    return new WorkloadProfile(
        name,
        concurrentUsers,
        durationMinutes,
        actionsPerUser,
        loginRampUpSeconds,
        setupTimeoutMinutes,
        actionPaceSeconds,
        rampDownGraceSeconds,
        measuredCounts,
        buildActionPlan(measuredCounts, measuredActionCount),
        rampActionsPerUser,
        rampCounts,
        buildActionPlan(rampCounts, maximumRampActionCount));
  }

  public static int minimumLoginRampUpSeconds(int concurrentUsers) {
    return (int) Math.ceil(concurrentUsers * LOGIN_INTERVAL_SECONDS);
  }

  /** Returns the fixed measured action for a dedicated account and its zero-based iteration. */
  public WorkloadAction actionFor(int accountOffset, int iteration) {
    return actionFor(actionPlan, actionsPerUser, accountOffset, iteration, "measured");
  }

  /** Returns the reserved ramp-up action for a dedicated account and its zero-based iteration. */
  public WorkloadAction rampActionFor(int accountOffset, int iteration) {
    return actionFor(rampActionPlan, rampActionsPerUser, accountOffset, iteration, "ramp-up");
  }

  public int scheduledActionCount(WorkloadAction action) {
    return scheduledActionCounts.getOrDefault(action, 0);
  }

  public int rampScheduledActionCount(WorkloadAction action) {
    return rampScheduledActionCounts.getOrDefault(action, 0);
  }

  public int maximumRampActionCount() {
    return Math.multiplyExact(concurrentUsers, rampActionsPerUser);
  }

  public Duration loginRampUpDuration() {
    return Duration.ofSeconds(loginRampUpSeconds);
  }

  public Duration authenticationSetupTimeout() {
    return Duration.ofMinutes(authenticationSetupTimeoutMinutes);
  }

  public Duration steadyStateDuration() {
    return Duration.ofMinutes(durationMinutes);
  }

  public Duration actionPace() {
    return durationFromSeconds(actionPaceSeconds);
  }

  public Duration maximumSimulationDuration() {
    return authenticationSetupTimeout()
        .plus(steadyStateDuration())
        .plusSeconds(rampDownGraceSeconds);
  }

  private static WorkloadAction actionFor(
      List<WorkloadAction> plan,
      int actionsPerUser,
      int accountOffset,
      int iteration,
      String phase) {
    int users = plan.size() / actionsPerUser;
    if (accountOffset < 0 || accountOffset >= users) {
      throw new IllegalArgumentException("Account offset is outside the configured workload: " + accountOffset);
    }
    if (iteration < 0 || iteration >= actionsPerUser) {
      throw new IllegalArgumentException(
          "Action iteration is outside the configured " + phase + " workload: " + iteration);
    }
    return plan.get(accountOffset * actionsPerUser + iteration);
  }

  private static Map<WorkloadAction, Integer> scaledScheduledCounts(
      Map<WorkloadAction, Integer> configuredCounts,
      int configuredActionCount,
      int targetActionCount) {
    if (configuredCounts.values().stream().mapToInt(Integer::intValue).sum() != configuredActionCount) {
      throw new IllegalArgumentException(
          "Configured scheduled action counts do not total " + configuredActionCount);
    }
    Map<WorkloadAction, Integer> counts = new EnumMap<>(WorkloadAction.class);
    Map<WorkloadAction, Long> remainders = new EnumMap<>(WorkloadAction.class);
    int allocated = 0;
    for (WorkloadAction action : WorkloadAction.values()) {
      long scaled = (long) configuredCounts.getOrDefault(action, 0) * targetActionCount;
      counts.put(action, (int) (scaled / configuredActionCount));
      remainders.put(action, scaled % configuredActionCount);
      allocated += counts.get(action);
    }
    List<WorkloadAction> byRemainder = new ArrayList<>(List.of(WorkloadAction.values()));
    byRemainder.sort((left, right) -> Long.compare(remainders.get(right), remainders.get(left)));
    for (int index = 0; allocated < targetActionCount; index++, allocated++) {
      WorkloadAction action = byRemainder.get(index);
      counts.put(action, counts.get(action) + 1);
    }
    return counts;
  }

  /**
   * Produces an evenly interleaved deterministic plan using largest current deficit. It preserves
   * every configured action total exactly, rather than sampling weights at runtime.
   */
  private static List<WorkloadAction> buildActionPlan(
      Map<WorkloadAction, Integer> counts, int expectedActionCount) {
    int configuredActionCount = counts.values().stream().mapToInt(Integer::intValue).sum();
    if (configuredActionCount != expectedActionCount) {
      throw new IllegalArgumentException("Scheduled action counts total " + configuredActionCount
          + "; expected " + expectedActionCount);
    }
    Map<WorkloadAction, Integer> allocated = new EnumMap<>(WorkloadAction.class);
    for (WorkloadAction action : WorkloadAction.values()) {
      allocated.put(action, 0);
    }
    List<WorkloadAction> plan = new ArrayList<>(expectedActionCount);
    for (int slot = 0; slot < expectedActionCount; slot++) {
      WorkloadAction selected = null;
      long largestDeficit = Long.MIN_VALUE;
      for (WorkloadAction candidate : WorkloadAction.values()) {
        int requested = counts.getOrDefault(candidate, 0);
        if (allocated.get(candidate) == requested) continue;
        long deficit = (long) (slot + 1) * requested
            - (long) allocated.get(candidate) * expectedActionCount;
        if (selected == null || deficit > largestDeficit) {
          selected = candidate;
          largestDeficit = deficit;
        }
      }
      if (selected == null) {
        throw new IllegalArgumentException("No action was available for schedule slot " + slot);
      }
      allocated.put(selected, allocated.get(selected) + 1);
      plan.add(selected);
    }
    return plan;
  }

  private static void requirePlan(
      String phase,
      Map<WorkloadAction, Integer> counts,
      List<WorkloadAction> plan,
      int expectedActionCount) {
    if (plan.size() != expectedActionCount) {
      throw new IllegalArgumentException("Deterministic " + phase + " action plan has " + plan.size()
          + " slots; expected " + expectedActionCount);
    }
    for (WorkloadAction action : WorkloadAction.values()) {
      int scheduledCount = counts.getOrDefault(action, 0);
      if (scheduledCount < 0) {
        throw new IllegalArgumentException("Scheduled action count must not be negative: " + action.key());
      }
      if (plan.stream().filter(action::equals).count() != scheduledCount) {
        throw new IllegalArgumentException(
            "Deterministic " + phase + " action plan does not match " + action.key());
      }
    }
  }

  private static int maximumActionsPerUser(int setupTimeoutMinutes, double actionPaceSeconds) {
    requireMinutes("Workload authentication setup timeout", setupTimeoutMinutes);
    requireActionPace(actionPaceSeconds);
    // The first action can start immediately after login. Ceil therefore covers every action that
    // can start before the setup deadline, including a final partial pace interval.
    return (int) Math.ceil(setupTimeoutMinutes * 60.0 / actionPaceSeconds);
  }

  private static Duration durationFromSeconds(double seconds) {
    return Duration.ofMillis(Math.max(1L, (long) Math.ceil(seconds * 1_000.0)));
  }

  private static void requireMinutes(String name, int value) {
    if (value < 1 || value > MAX_DURATION_MINUTES) {
      throw new IllegalArgumentException(name + " must be between 1 and " + MAX_DURATION_MINUTES + " minutes");
    }
  }

  private static void requireActionPace(double value) {
    if (!Double.isFinite(value)
        || value < MINIMUM_ACTION_PACE_SECONDS
        || value > MAXIMUM_ACTION_PACE_SECONDS) {
      throw new IllegalArgumentException("Workload action pace must be between 60 and 3600 seconds");
    }
  }

  private static Properties loadProperties() {
    Path profilePath = Path.of(System.getProperty(
        "appRegWorkloadProfileFile", "data/seed/workload/allocation-profile.properties"));
    if (!Files.isRegularFile(profilePath)) {
      throw new IllegalArgumentException("Workload allocation profile was not found: " + profilePath.toAbsolutePath());
    }
    Properties properties = new Properties();
    try (var reader = Files.newBufferedReader(profilePath)) {
      properties.load(reader);
      return properties;
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "Could not read workload allocation profile: " + profilePath.toAbsolutePath(), exception);
    }
  }

  private static int cappedUsers(int configuredUsers) {
    String requestedValue = System.getProperty(MAX_USERS_PROPERTY);
    if (requestedValue == null) return configuredUsers;
    int requestedUsers = parseInteger(MAX_USERS_PROPERTY, requestedValue);
    if (requestedUsers < 1) {
      throw new IllegalArgumentException(MAX_USERS_PROPERTY + " must be at least 1");
    }
    return Math.min(requestedUsers, configuredUsers);
  }

  private static int cappedDuration(int configuredDurationMinutes) {
    String requestedValue = System.getProperty(DURATION_MINUTES_PROPERTY);
    if (requestedValue == null) return configuredDurationMinutes;
    int requestedMinutes = parseInteger(DURATION_MINUTES_PROPERTY, requestedValue);
    if (requestedMinutes < 1) {
      throw new IllegalArgumentException(DURATION_MINUTES_PROPERTY + " must be a positive integer");
    }
    return Math.min(requestedMinutes, configuredDurationMinutes);
  }

  private static Map<WorkloadAction, Integer> scheduledCounts(Properties properties, String name) {
    Map<WorkloadAction, Integer> counts = new EnumMap<>(WorkloadAction.class);
    for (WorkloadAction action : WorkloadAction.values()) {
      counts.put(action, nonNegative(properties, name + ".scheduled_" + action.key()));
    }
    return counts;
  }

  private static int positive(Properties properties, String key) {
    int value = nonNegative(properties, key);
    if (value < 1) {
      throw new IllegalArgumentException("Workload allocation profile value must be positive: " + key);
    }
    return value;
  }

  private static int nonNegative(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("Workload allocation profile is missing " + key);
    }
    int parsed = parseInteger(key, value);
    if (parsed < 0) {
      throw new IllegalArgumentException("Workload allocation profile value must not be negative: " + key);
    }
    return parsed;
  }

  private static int integerProperty(String name, int defaultValue) {
    String value = System.getProperty(name);
    return value == null ? defaultValue : parseInteger(name, value);
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

  private static int parseInteger(String name, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(name + " must be an integer", exception);
    }
  }

  /** Small dependency-free check for ramp capacity and deterministic scaling. */
  public static void main(String[] args) {
    if (maximumActionsPerUser(15, 60) != 15 || maximumActionsPerUser(15, 120) != 8) {
      throw new IllegalStateException("Workload ramp capacity calculation failed");
    }
    Map<WorkloadAction, Integer> configured = new EnumMap<>(WorkloadAction.class);
    for (WorkloadAction action : WorkloadAction.values()) configured.put(action, 0);
    configured.put(WorkloadAction.UPDATE_APPLICATION, 3);
    configured.put(WorkloadAction.OTHER_OPERATIONS, 1);
    Map<WorkloadAction, Integer> scaled = scaledScheduledCounts(configured, 4, 8);
    if (scaled.get(WorkloadAction.UPDATE_APPLICATION) != 6
        || scaled.get(WorkloadAction.OTHER_OPERATIONS) != 2
        || buildActionPlan(scaled, 8).size() != 8) {
      throw new IllegalStateException("Workload deterministic scaling failed");
    }
    expectInvalid(() -> maximumActionsPerUser(15, 59));
    expectInvalid(() -> maximumActionsPerUser(0, 60));
    System.out.println("Workload profile self-check passed");
  }

  private static void expectInvalid(Runnable action) {
    try {
      action.run();
      throw new IllegalStateException("Expected invalid workload configuration to be rejected");
    } catch (IllegalArgumentException expected) {
      // Intentionally empty: each invalid boundary must reject construction.
    }
  }
}
