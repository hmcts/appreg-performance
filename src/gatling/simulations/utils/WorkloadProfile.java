package utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Runtime configuration, exact action plan and feeder allocation expectations for a workload. */
public record WorkloadProfile(
    String name,
    int concurrentUsers,
    int durationMinutes,
    int actionsPerUser,
    int loginRampUpSeconds,
    Map<WorkloadAction, Integer> scheduledActionCounts,
    List<WorkloadAction> actionPlan,
    int updateApplicationCount,
    int addApplicationCount,
    int resultApplicationCount,
    int resultMultipleCount,
    int updateResultCount,
    int updateListCount,
    int closeListCount,
    int bulkOfficialsCount,
    int bulkFeesCount,
    int bulkUploadCount) {

  private static final int MAX_TEST_ACCOUNTS = 500;
  private static final int SAFE_LOGINS_PER_SECOND = 10;
  private static final String MAX_USERS_PROPERTY = "appRegMaxUsers";
  private static final String DURATION_MINUTES_PROPERTY = "appRegDurationMinutes";

  public WorkloadProfile {
    if ("smoke".equals(name)) {
      throw new IllegalArgumentException("appRegWorkloadProfile=smoke is for one-user proofs, not a workload run");
    }
    if (concurrentUsers < 1 || concurrentUsers > MAX_TEST_ACCOUNTS) {
      throw new IllegalArgumentException("Workload concurrent users must be between 1 and " + MAX_TEST_ACCOUNTS);
    }
    if (durationMinutes < 1 || durationMinutes > 75) {
      throw new IllegalArgumentException("Workload duration must be between 1 and 75 minutes");
    }
    if (actionsPerUser != durationMinutes) {
      throw new IllegalArgumentException("Workload currently requires one planned action per user per minute");
    }
    if (loginRampUpSeconds < minimumLoginRampUpSeconds(concurrentUsers)) {
      throw new IllegalArgumentException(
          "Workload login ramp must allow no more than " + SAFE_LOGINS_PER_SECOND + " logins per second");
    }
    scheduledActionCounts = Map.copyOf(scheduledActionCounts);
    actionPlan = List.copyOf(actionPlan);
    int expectedActionCount = Math.multiplyExact(concurrentUsers, actionsPerUser);
    if (actionPlan.size() != expectedActionCount) {
      throw new IllegalArgumentException("Deterministic action plan has " + actionPlan.size()
          + " slots; expected " + expectedActionCount);
    }
    for (WorkloadAction action : WorkloadAction.values()) {
      int scheduledCount = scheduledActionCounts.getOrDefault(action, 0);
      if (scheduledCount < 0) {
        throw new IllegalArgumentException("Scheduled action count must not be negative: " + action.key());
      }
      if (actionPlan.stream().filter(action::equals).count() != scheduledCount) {
        throw new IllegalArgumentException("Deterministic action plan does not match scheduled count for " + action.key());
      }
    }
    requireCapacity(scheduledActionCounts, WorkloadAction.UPDATE_APPLICATION, updateApplicationCount);
    requireCapacity(scheduledActionCounts, WorkloadAction.ADD_APPLICATION, addApplicationCount);
    requireCapacity(scheduledActionCounts, WorkloadAction.RESULT_APPLICATION, resultApplicationCount);
    requireCapacity(scheduledActionCounts, WorkloadAction.RESULT_MULTIPLE, resultMultipleCount);
    requireCapacity(scheduledActionCounts, WorkloadAction.UPDATE_RESULT, updateResultCount);
    requireCapacity(scheduledActionCounts, WorkloadAction.UPDATE_LIST, updateListCount);
    requireCapacity(scheduledActionCounts, WorkloadAction.CLOSE_LIST, closeListCount);
    requireCapacity(scheduledActionCounts, WorkloadAction.BULK_OFFICIALS, bulkOfficialsCount);
    requireCapacity(scheduledActionCounts, WorkloadAction.BULK_FEES, bulkFeesCount);
    requireCapacity(scheduledActionCounts, WorkloadAction.BULK_UPLOAD, bulkUploadCount);
  }

  public static WorkloadProfile fromRuntime() {
    String name = System.getProperty("appRegWorkloadProfile", System.getenv().getOrDefault("WORKLOAD_PROFILE", "smoke"));
    if ("smoke".equals(name)) {
      throw new IllegalArgumentException("appRegWorkloadProfile=smoke is for one-user proofs, not a workload run");
    }
    Properties properties = loadProperties();
    int configuredUsers = positive(properties, name + ".concurrent_users");
    int concurrentUsers = cappedUsers(configuredUsers);
    int configuredDurationMinutes = positive(properties, name + ".duration_minutes");
    int durationMinutes = cappedDuration(configuredDurationMinutes);
    int actionsPerUser = durationMinutes;
    Map<WorkloadAction, Integer> scheduledCounts = cappedScheduledCounts(
      scheduledCounts(properties, name), configuredUsers, configuredDurationMinutes, concurrentUsers, actionsPerUser);
    List<WorkloadAction> actionPlan = buildActionPlan(scheduledCounts, Math.multiplyExact(concurrentUsers, actionsPerUser));
    return new WorkloadProfile(
        name,
        concurrentUsers,
        durationMinutes,
        actionsPerUser,
        Math.min(positive(properties, name + ".login_ramp_up_seconds"), concurrentUsers),
        scheduledCounts,
        actionPlan,
        nonNegative(properties, name + ".update_application"),
        nonNegative(properties, name + ".add_application"),
        nonNegative(properties, name + ".result_application"),
        nonNegative(properties, name + ".result_multiple"),
        nonNegative(properties, name + ".update_result"),
        nonNegative(properties, name + ".update_list"),
        nonNegative(properties, name + ".close_list"),
        nonNegative(properties, name + ".bulk_officials"),
        nonNegative(properties, name + ".bulk_fees"),
        nonNegative(properties, name + ".bulk_upload"));
  }

  public static int minimumLoginRampUpSeconds(int concurrentUsers) {
    return (int) Math.ceil((double) concurrentUsers / SAFE_LOGINS_PER_SECOND);
  }

  private static int cappedUsers(int configuredUsers) {
    String requestedValue = System.getProperty(MAX_USERS_PROPERTY);
    if (requestedValue == null) return configuredUsers;
    final int requestedUsers;
    try {
      requestedUsers = Integer.parseInt(requestedValue);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(MAX_USERS_PROPERTY + " must be a positive integer", exception);
    }
    if (requestedUsers < 1) {
      throw new IllegalArgumentException(MAX_USERS_PROPERTY + " must be at least 1");
    }
    return Math.min(requestedUsers, configuredUsers);
  }

  private static int cappedDuration(int configuredDurationMinutes) {
    String requestedValue = System.getProperty(DURATION_MINUTES_PROPERTY);
    if (requestedValue == null) return configuredDurationMinutes;
    try {
      int requestedMinutes = Integer.parseInt(requestedValue);
      if (requestedMinutes < 1) throw new NumberFormatException();
      return Math.min(requestedMinutes, configuredDurationMinutes);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(DURATION_MINUTES_PROPERTY + " must be a positive integer", exception);
    }
  }

  private static Map<WorkloadAction, Integer> cappedScheduledCounts(
      Map<WorkloadAction, Integer> configuredCounts, int configuredUsers, int configuredActionsPerUser,
      int cappedUsers, int actionsPerUser) {
    int cappedActionCount = Math.multiplyExact(cappedUsers, actionsPerUser);
    int configuredActionCount = Math.multiplyExact(configuredUsers, configuredActionsPerUser);
    if (cappedActionCount == configuredActionCount) return configuredCounts;

    Map<WorkloadAction, Integer> counts = new EnumMap<>(WorkloadAction.class);
    Map<WorkloadAction, Long> remainders = new EnumMap<>(WorkloadAction.class);
    int allocated = 0;
    for (WorkloadAction action : WorkloadAction.values()) {
      long scaled = (long) configuredCounts.get(action) * cappedActionCount;
      counts.put(action, (int) (scaled / configuredActionCount));
      remainders.put(action, scaled % configuredActionCount);
      allocated += counts.get(action);
    }
    List<WorkloadAction> byRemainder = new ArrayList<>(List.of(WorkloadAction.values()));
    byRemainder.sort((left, right) -> Long.compare(remainders.get(right), remainders.get(left)));
    for (int index = 0; allocated < cappedActionCount; index++, allocated++) {
      WorkloadAction action = byRemainder.get(index);
      counts.put(action, counts.get(action) + 1);
    }
    return counts;
  }

  /** Returns the fixed action for a dedicated account and its zero-based iteration. */
  public WorkloadAction actionFor(int accountOffset, int iteration) {
    if (accountOffset < 0 || accountOffset >= concurrentUsers) {
      throw new IllegalArgumentException("Account offset is outside the configured workload: " + accountOffset);
    }
    if (iteration < 0 || iteration >= actionsPerUser) {
      throw new IllegalArgumentException("Action iteration is outside the configured workload: " + iteration);
    }
    return actionPlan.get(accountOffset * actionsPerUser + iteration);
  }

  public int scheduledActionCount(WorkloadAction action) {
    return scheduledActionCounts.getOrDefault(action, 0);
  }

  private static void requireCapacity(
      Map<WorkloadAction, Integer> scheduledActionCounts, WorkloadAction action, int allocationCount) {
    int scheduledCount = scheduledActionCounts.getOrDefault(action, 0);
    if (allocationCount < scheduledCount) {
      throw new IllegalArgumentException("Allocated data for " + action.key() + " is " + allocationCount
          + "; deterministic schedule requires " + scheduledCount);
    }
  }

  private static Map<WorkloadAction, Integer> scheduledCounts(Properties properties, String name) {
    Map<WorkloadAction, Integer> counts = new EnumMap<>(WorkloadAction.class);
    for (WorkloadAction action : WorkloadAction.values()) {
      counts.put(action, nonNegative(properties, name + ".scheduled_" + action.key()));
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
        int requested = counts.get(candidate);
        if (allocated.get(candidate) == requested) continue;
        long deficit = (long) (slot + 1) * requested - (long) allocated.get(candidate) * expectedActionCount;
        if (selected == null || deficit > largestDeficit) {
          selected = candidate;
          largestDeficit = deficit;
        }
      }
      if (selected == null) throw new IllegalArgumentException("No action was available for schedule slot " + slot);
      allocated.put(selected, allocated.get(selected) + 1);
      plan.add(selected);
    }
    return plan;
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
      throw new IllegalArgumentException("Could not read workload allocation profile: " + profilePath.toAbsolutePath(), exception);
    }
  }

  private static int positive(Properties properties, String key) {
    int value = nonNegative(properties, key);
    if (value < 1) throw new IllegalArgumentException("Workload allocation profile value must be positive: " + key);
    return value;
  }

  private static int nonNegative(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("Workload allocation profile is missing " + key);
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 0) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Workload allocation profile value must be a non-negative integer: " + key);
    }
  }
}
