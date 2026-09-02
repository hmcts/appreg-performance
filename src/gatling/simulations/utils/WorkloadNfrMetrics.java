package utils;

import io.gatling.javaapi.core.Session;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Captures logical measured-group durations after safe gateway-retry overhead is removed. */
public final class WorkloadNfrMetrics {
  private static final String START_NANOS_KEY = "workloadNfrStartNanos";
  private static final String EXCLUDED_MILLIS_KEY = "workloadNfrExcludedMillis";

  private final Map<WorkloadAction, Collection<Long>> durations =
      new EnumMap<>(WorkloadAction.class);
  private final Map<WorkloadAction, Collection<Boolean>> attempts =
      new EnumMap<>(WorkloadAction.class);

  public WorkloadNfrMetrics() {
    for (var action : WorkloadAction.values()) {
      durations.put(action, new ConcurrentLinkedQueue<>());
      attempts.put(action, new ConcurrentLinkedQueue<>());
    }
  }

  public Session start(Session session) {
    return session
        .set(START_NANOS_KEY, System.nanoTime())
        .set(EXCLUDED_MILLIS_KEY, 0L);
  }

  public Session complete(WorkloadAction action, Session session) {
    boolean succeeded = !session.isFailed();
    attempts.get(action).add(succeeded);
    if (succeeded) {
      long elapsedMillis = (System.nanoTime() - session.getLong(START_NANOS_KEY)) / 1_000_000;
      long logicalMillis = logicalDurationMillis(
          elapsedMillis, session.getLong(EXCLUDED_MILLIS_KEY));
      durations.get(action).add(logicalMillis);
    }
    // Each action has isolated data, so a failed action must be reported without silently
    // discarding the actor's remaining plan. Gatling still retains any request KO in its stats.
    return session
        .removeAll(START_NANOS_KEY, EXCLUDED_MILLIS_KEY)
        .markAsSucceeded();
  }

  public int attemptedActions(WorkloadAction action) {
    return attempts.get(action).size();
  }

  static Session excludeGatewayOverhead(Session session, long millis) {
    if (!session.contains(EXCLUDED_MILLIS_KEY)) return session;
    return session.set(
        EXCLUDED_MILLIS_KEY,
        Math.addExact(session.getLong(EXCLUDED_MILLIS_KEY), millis));
  }

  public int completedActions(WorkloadAction action) {
    return durations.get(action).size();
  }

  public int failedActions(WorkloadAction action) {
    return (int) attempts.get(action).stream().filter(succeeded -> !succeeded).count();
  }

  public long p95Millis(WorkloadAction action) {
    return GatewayRetryPolicy.percentile95(durations.get(action));
  }

  static void selfCheck() {
    if (logicalDurationMillis(1_500, 1_200) != 300
        || logicalDurationMillis(900, 1_000) != 0) {
      throw new IllegalStateException("Workload logical timing self-check failed");
    }
  }

  private static long logicalDurationMillis(long elapsedMillis, long excludedMillis) {
    return Math.max(0, elapsedMillis - excludedMillis);
  }
}
