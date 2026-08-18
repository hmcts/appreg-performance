package utils;

public final class Environment {
  private static final String DEFAULT_BASE_URL = "https://appreg.test.apps.hmcts.net";

  private static final String TEST_URL = System.getenv("TEST_URL");
  public static final String BASE_URL = (TEST_URL == null || TEST_URL.isEmpty() ? DEFAULT_BASE_URL : TEST_URL)
    .replaceFirst("/+$", "");
  public static final String APPLICATIONS_LIST_PATH = "/applications-list";

  /** Returns a required environment value without ever including its value in an error message. */
  public static String requiredEnvironmentVariable(String name) {
    var value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Set " + name + " to run this seeded proof");
    }
    return value;
  }

  private Environment() {}
}
