package utils;

public final class Environment {
  private static final String DEFAULT_BASE_URL = "https://appreg.test.apps.hmcts.net";

  private static final String TEST_URL = System.getenv("TEST_URL");
  public static final String BASE_URL = (TEST_URL == null || TEST_URL.isEmpty() ? DEFAULT_BASE_URL : TEST_URL)
    .replaceFirst("/+$", "");
  public static final String APPLICATIONS_LIST_PATH = "/applications-list";

  private Environment() {}
}
