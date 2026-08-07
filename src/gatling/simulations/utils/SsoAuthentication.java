package utils;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.IntStream;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.regex;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.headerRegex;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/** Replays AppReg's SSO protocol at HTTP level without writing credentials to disk or logs. */
public final class SsoAuthentication {
  private static final String MICROSOFT_LOGIN_BASE_URL = "https://login.microsoftonline.com";
  private static final Map<String, String> BROWSER_HEADERS = Map.of(
    "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language", "en-GB,en;q=0.9",
    "Upgrade-Insecure-Requests", "1"
  );
  private static final String CREDENTIAL_DISCOVERY_BODY = "{\"username\":\"#{username}\",\"isOtherIdpSupported\":true,\"checkPhones\":false,\"isRemoteNGCSupported\":true,\"isCookieBannerShown\":false,\"isFidoSupported\":true,\"country\":\"GB\",\"forceotclogin\":false,\"isExternalFederationDisallowed\":false,\"isRemoteConnectSupported\":false,\"federationFlags\":0,\"isSignup\":false,\"flowToken\":\"#{entraFlowToken}\",\"isAccessPassSupported\":true,\"isQrCodePinSupported\":true}";

  private SsoAuthentication() {}

  private static String environmentVariable(String... names) {
    for (String name : names) {
      String value = System.getenv(name);
      if (value != null && !value.isEmpty()) return value;
    }
    return null;
  }

  private static String requiredEnvironmentVariable(String... names) {
    String value = environmentVariable(names);
    if (value == null) throw new IllegalArgumentException("Set one of: " + String.join(", ", names));
    return value;
  }

  private static String accountName(String template, int index, int accountCount) {
    if (template.contains("{index}")) return template.replace("{index}", Integer.toString(index));
    if (accountCount == 1) return template;
    throw new IllegalArgumentException("TEST_USER_EMAIL or APPREG_TEST_ACCOUNT_TEMPLATE must contain {index} for an SSO run with multiple users");
  }

  /** One dedicated account per virtual user. The password is never added to a feeder. */
  public static Iterator<Map<String, Object>> users(int accountCount) {
    if (accountCount <= 0) throw new IllegalArgumentException("The SSO account count must be greater than zero");
    String template = requiredEnvironmentVariable("APPREG_TEST_ACCOUNT_TEMPLATE", "TEST_USER_EMAIL");
    String startIndex = environmentVariable("APPREG_ACCOUNT_START_INDEX");
    int firstIndex = startIndex == null ? 1 : Integer.parseInt(startIndex);
    return IntStream.range(firstIndex, firstIndex + accountCount)
      .mapToObj(index -> Map.<String, Object>of("username", accountName(template, index, accountCount)))
      .iterator();
  }

  public static ChainBuilder login() {
    String password = requiredEnvironmentVariable("APPREG_TEST_USER_PASSWORD", "TEST_USERS_PASSWORD");
    String tenantId = requiredEnvironmentVariable("APPREG_TENANT_ID", "TENANT_ID");
    return group("AppReg_000_SSO_Login").on(
      exec(http("AppReg SSO login redirect").get("/sso/login").headers(BROWSER_HEADERS).disableFollowRedirect()
        .check(status().is(302)).check(headerRegex("Location", "(.+)").saveAs("entraAuthorizeUrl")))
        .exec(http("Entra authorize").get("#{entraAuthorizeUrl}")
          .headers(Map.of("Accept", BROWSER_HEADERS.get("Accept"), "Accept-Language", BROWSER_HEADERS.get("Accept-Language"), "Upgrade-Insecure-Requests", "1", "Referer", Environment.BASE_URL + "/login"))
          .check(status().is(200)).check(regex("(?s).*?\\\"sessionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").saveAs("entraSessionId"))
          .check(regex("(?s).*?\\\"sCtx\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").saveAs("entraContext"))
          .check(regex("(?s).*?\\\"sFT\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").saveAs("entraFlowToken"))
          .check(regex("(?s).*?\\\"canary\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").saveAs("entraCanary")))
        .exec(http("Entra credential discovery").post(MICROSOFT_LOGIN_BASE_URL + "/common/GetCredentialType?mkt=en-GB")
          .headers(Map.of("Accept", "application/json, text/javascript, */*; q=0.01", "Content-Type", "application/json; charset=UTF-8", "Origin", MICROSOFT_LOGIN_BASE_URL, "Referer", "#{entraAuthorizeUrl}", "canary", "#{entraCanary}", "hpgrequestid", "#{entraSessionId}"))
          .body(StringBody(CREDENTIAL_DISCOVERY_BODY)).asJson().check(status().is(200)))
        .exec(http("Entra username and password").post(MICROSOFT_LOGIN_BASE_URL + "/" + tenantId + "/login")
          .headers(Map.of("Accept", BROWSER_HEADERS.get("Accept"), "Content-Type", "application/x-www-form-urlencoded", "Origin", MICROSOFT_LOGIN_BASE_URL, "Referer", "#{entraAuthorizeUrl}"))
          .formParam("i13", "0").formParam("login", "#{username}").formParam("loginfmt", "#{username}").formParam("type", "11").formParam("LoginOptions", "3").formParam("lrt", "").formParam("lrtPartition", "").formParam("hisRegion", "").formParam("hisScaleUnit", "").formParam("passwd", password).formParam("ps", "2").formParam("psRNGCDefaultType", "").formParam("psRNGCEntropy", "").formParam("psRNGCSLK", "").formParam("canary", "#{entraCanary}").formParam("ctx", "#{entraContext}").formParam("hpgrequestid", "#{entraSessionId}").formParam("flowToken", "#{entraFlowToken}").formParam("PPSX", "").formParam("NewUser", "1").formParam("FoundMSAs", "").formParam("fspost", "0").formParam("i21", "0").formParam("CookieDisclosure", "0").formParam("IsFidoSupported", "1").formParam("isSignupPost", "0").formParam("DfpArtifact", "").formParam("i19", "3306")
          .check(status().is(200))
          .check(regex("(?s).*?\\\"sessionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraSessionId"))
          .check(regex("(?s).*?\\\"sCtx\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraContext"))
          .check(regex("(?s).*?\\\"sFT\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraFlowToken"))
          .check(regex("(?s).*?\\\"canary\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraCanary")))
        .doIf(SsoAuthentication::hasKmsiContinuation).then(
          exec(http("Entra KMSI").post(MICROSOFT_LOGIN_BASE_URL + "/kmsi")
          .headers(Map.of("Accept", BROWSER_HEADERS.get("Accept"), "Content-Type", "application/x-www-form-urlencoded", "Origin", MICROSOFT_LOGIN_BASE_URL, "Referer", MICROSOFT_LOGIN_BASE_URL + "/" + tenantId + "/login"))
          .formParam("LoginOptions", "3").formParam("type", "28").formParam("ctx", "#{entraContext}").formParam("hpgrequestid", "#{entraSessionId}").formParam("flowToken", "#{entraFlowToken}").formParam("canary", "#{entraCanary}").formParam("i19", "7178").check(status().in(200, 302))))
        .exec(http("AppReg authenticated home").get("/").headers(BROWSER_HEADERS).check(status().is(200)))
        .exec(http("AppReg session check").get("/sso/me").header("Accept", "application/json").check(status().is(200)).check(jsonPath("$.authenticated").is("true")))
    );
  }

  private static boolean hasKmsiContinuation(Session session) {
    return session.contains("entraSessionId")
      && session.contains("entraContext")
      && session.contains("entraFlowToken")
      && session.contains("entraCanary");
  }
}
