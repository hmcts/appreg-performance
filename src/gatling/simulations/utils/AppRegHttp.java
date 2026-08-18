package utils;

import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.http.HttpDsl.http;

/** Shared HTTP protocol settings for curated AppReg simulations. */
public final class AppRegHttp {
  private AppRegHttp() {}

  public static HttpProtocolBuilder protocol() {
    return http.baseUrl(Environment.BASE_URL)
      .userAgentHeader(Headers.USER_AGENT)
      .doNotTrackHeader("1")
      .inferHtmlResources()
      .silentResources();
  }
}
