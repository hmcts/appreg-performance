package utils;

import java.util.Map;

public final class Headers {
  public static final Map<String, String> COMMON_HEADER = Map.ofEntries(
    Map.entry("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9"),
    Map.entry("accept-encoding", "gzip, deflate, br"),
    Map.entry("accept-language", "en-GB,en;q=0.9"),
    Map.entry("sec-fetch-dest", "document"),
    Map.entry("sec-fetch-mode", "navigate"),
    Map.entry("sec-fetch-site", "same-origin"),
    Map.entry("sec-fetch-user", "?1"),
    Map.entry("upgrade-insecure-requests", "1")
  );

  private Headers() {}
}
