package utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/** Pure gateway retry and nearest-rank percentile rules shared by the workload modes. */
public final class GatewayRetryPolicy {
  private GatewayRetryPolicy() {}

  public static boolean isTransient(int statusCode) {
    return statusCode == 502 || statusCode == 504;
  }

  public static boolean shouldRetry(int statusCode, int attempt, int retries) {
    return isTransient(statusCode) && attempt <= retries;
  }

  /** True when this response must fail its Gatling request rather than being recovered. */
  public static boolean shouldFailRequest(int statusCode, int completedAttempts, int retries) {
    return !isTransient(statusCode) || completedAttempts >= retries;
  }

  public static long percentile95(Collection<Long> durations) {
    if (durations.isEmpty()) return -1;
    var sorted = new ArrayList<>(durations);
    Collections.sort(sorted);
    int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
    return sorted.get(index);
  }

  static void selfCheck() {
    if (!isTransient(502)
        || !isTransient(504)
        || isTransient(500)
        || !shouldRetry(502, 1, 1)
        || shouldRetry(504, 2, 1)
        || shouldFailRequest(502, 0, 1)
        || !shouldFailRequest(504, 1, 1)
        || !shouldFailRequest(500, 0, 1)
        || percentile95(java.util.List.of(100L, 200L, 300L, 400L, 500L)) != 500L) {
      throw new IllegalStateException("Gateway retry policy self-check failed");
    }
  }
}
