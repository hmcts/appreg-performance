package utils;

import java.util.Map;

public final class Headers {
  public static final String APPREG_API_MEDIA_TYPE = "application/vnd.hmcts.appreg.v1+json";
  public static final String APPREG_SESSION_COOKIE = System.getProperty(
    "appRegSessionCookieName",
    System.getenv().getOrDefault("APPREG_SESSION_COOKIE_NAME", "appreg.sid"));
  public static final String XSRF_TOKEN_COOKIE = "XSRF-TOKEN";
  public static final String XSRF_TOKEN_HEADER = "X-XSRF-TOKEN";
  public static final String USER_AGENT = System.getProperty(
    "appRegUserAgent",
    System.getenv().getOrDefault("APPREG_USER_AGENT", "Gatling Performance Runner"));

  public static final Map<String, String> COMMON_HEADER = Map.ofEntries(
    Map.entry("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9"),
    Map.entry("accept-encoding", "gzip, deflate, br"),
    Map.entry("accept-language", "en-GB,en;q=0.9"),
    Map.entry("sec-fetch-dest", "document"),
    Map.entry("sec-fetch-mode", "navigate"),
    Map.entry("sec-fetch-site", "same-origin"),
    Map.entry("sec-fetch-user", "?1"),
    Map.entry("upgrade-insecure-requests", "1"),
    Map.entry("user-agent", USER_AGENT)
  );

  private Headers() {}
}
