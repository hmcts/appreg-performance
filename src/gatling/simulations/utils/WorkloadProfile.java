package utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Runtime configuration and allocated-data expectations for a bounded AppReg workload. */
public record WorkloadProfile(
    String name,
    int concurrentUsers,
    int durationMinutes,
    int loginRampUpSeconds,
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
    if (loginRampUpSeconds < minimumLoginRampUpSeconds(concurrentUsers)) {
      throw new IllegalArgumentException(
          "Workload login ramp must allow no more than " + SAFE_LOGINS_PER_SECOND + " logins per second");
    }
  }

  public static WorkloadProfile fromRuntime() {
    String name = System.getProperty("appRegWorkloadProfile", System.getenv().getOrDefault("WORKLOAD_PROFILE", "smoke"));
    if ("smoke".equals(name)) {
      throw new IllegalArgumentException("appRegWorkloadProfile=smoke is for one-user proofs, not a workload run");
    }
    Properties properties = loadProperties();
    int concurrentUsers = positive(properties, name + ".concurrent_users");
    return new WorkloadProfile(
        name,
        concurrentUsers,
        positive(properties, name + ".duration_minutes"),
        positive(properties, name + ".login_ramp_up_seconds"),
        positive(properties, name + ".update_application"),
        positive(properties, name + ".add_application"),
        positive(properties, name + ".result_application"),
        positive(properties, name + ".result_multiple"),
        positive(properties, name + ".update_result"),
        positive(properties, name + ".update_list"),
        positive(properties, name + ".close_list"),
        positive(properties, name + ".bulk_officials"),
        positive(properties, name + ".bulk_fees"),
        positive(properties, name + ".bulk_upload"));
  }

  public static int minimumLoginRampUpSeconds(int concurrentUsers) {
    return (int) Math.ceil((double) concurrentUsers / SAFE_LOGINS_PER_SECOND);
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
    String value = properties.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("Workload allocation profile is missing " + key);
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 1) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Workload allocation profile value must be a positive integer: " + key);
    }
  }
}
