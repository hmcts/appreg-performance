package utils;

/**
 * Runtime configuration for the shared AppReg simulation.
 *
 * <p>Defaults preserve the existing profile behaviour. Final workload values must be agreed with
 * the NFR owner and supported by allocated test data before being overridden.
 */
public record PerformanceProfile(
    int rampUpMinutes,
    int durationMinutes,
    int rampDownMinutes,
    double hourlyTarget,
    int pipelineRampMinutes,
    double successfulRequestsThreshold) {

  private static final int MAX_TOTAL_DURATION_MINUTES = 75;

  public PerformanceProfile {
    if (rampUpMinutes + durationMinutes + rampDownMinutes > MAX_TOTAL_DURATION_MINUTES) {
      throw new IllegalArgumentException(
          "The combined ramp-up, duration and ramp-down must not exceed "
              + MAX_TOTAL_DURATION_MINUTES
              + " minutes");
    }
  }

  public static PerformanceProfile fromRuntime() {
    return new PerformanceProfile(
        positiveInteger("appRegRampUpMinutes", 5),
        positiveInteger("appRegDurationMinutes", 60),
        positiveInteger("appRegRampDownMinutes", 5),
        positiveDouble("appRegHourlyTarget", 10.0),
        positiveInteger("appRegPipelineRampMinutes", 2),
        percentage("appRegSuccessfulRequestsThreshold", 95.0)
    );
  }

  private static int positiveInteger(String propertyName, int defaultValue) {
    int value = Integer.getInteger(propertyName, defaultValue);
    if (value <= 0) {
      throw new IllegalArgumentException(propertyName + " must be greater than zero");
    }
    return value;
  }

  private static double positiveDouble(String propertyName, double defaultValue) {
    double value = Double.parseDouble(System.getProperty(propertyName, Double.toString(defaultValue)));
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
