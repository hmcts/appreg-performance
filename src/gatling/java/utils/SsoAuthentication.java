package utils;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.CheckBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.regex;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.GatewayRetryPolicy.shouldFailRequest;
import static utils.GatewayRetryPolicy.shouldRetry;

/** Replays AppReg's SSO protocol at HTTP level without writing credentials to disk or logs. */
public final class SsoAuthentication {
  private static final String MICROSOFT_LOGIN_BASE_URL = "https://login.microsoftonline.com";
  private static final int MAX_TEST_ACCOUNTS = 500;
  private static final int FINAL_APPREG_GET_RETRIES = 1;
  private static final Duration FINAL_APPREG_GET_RETRY_DELAY = Duration.ofSeconds(1);
  private static final String FINAL_GET_ATTEMPT_KEY = "ssoFinalGetAttempt";
  private static final String FINAL_GET_PENDING_KEY = "ssoFinalGetRetryPending";
  private static final String FINAL_GET_STATUS_KEY = "ssoFinalGetStatus";
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
    if (template.contains("{index}")) return template.replace("{index}", "%03d".formatted(index));
    if (accountCount == 1) return template;
    throw new IllegalArgumentException("TEST_USER_EMAIL or APPREG_TEST_ACCOUNT_TEMPLATE must contain {index} for an SSO run with multiple users");
  }

  /** One dedicated account per virtual user. The password is never added to a feeder. */
  public static Iterator<Map<String, Object>> users(int accountCount) {
    if (accountCount <= 0) throw new IllegalArgumentException("The SSO account count must be greater than zero");
    if (accountCount > MAX_TEST_ACCOUNTS) {
      throw new IllegalArgumentException("The SSO account count must not exceed " + MAX_TEST_ACCOUNTS);
    }
    String template = requiredEnvironmentVariable("APPREG_TEST_ACCOUNT_TEMPLATE", "TEST_USER_EMAIL");
    String startIndex = environmentVariable("APPREG_ACCOUNT_START_INDEX");
    int firstIndex = startIndex == null ? 1 : Integer.parseInt(startIndex);
    return IntStream.range(firstIndex, firstIndex + accountCount)
      .mapToObj(index -> Map.<String, Object>of(
          "username", accountName(template, index, accountCount),
          // The deterministic workload plan uses this zero-based offset to assign a stable
          // action sequence to each dedicated account. Other simulations ignore it.
          "accountOffset", index - firstIndex))
      .iterator();
  }

  public static ChainBuilder login() {
    String password = requiredEnvironmentVariable("APPREG_TEST_USER_PASSWORD", "TEST_USERS_PASSWORD");
    return group("AppReg_000_SSO_Login").on(
      // AppReg starts the flow with a 302 to Microsoft Entra.
      exec(http("AppReg SSO login redirect").get("/sso/login").headers(BROWSER_HEADERS).disableFollowRedirect()
        .transformResponse(DiagnosticLogging.logIfStatusNotIn("AppReg SSO login redirect", Set.of(302)))
        .check(status().is(302)).check(header("Retry-After").optional().saveAs("retryAfter"))
        .check(header("Location").saveAs("entraAuthorizeUrl")))
        // Entra can return either the complete login configuration or a bootstrap shell whose
        // urlPost must first be loaded. Capture both shapes and select the next step below.
        .exec(http("Entra authorize").get("#{entraAuthorizeUrl}")
          .headers(Map.of("Accept", BROWSER_HEADERS.get("Accept"), "Accept-Language", BROWSER_HEADERS.get("Accept-Language"), "Upgrade-Insecure-Requests", "1", "Referer", Environment.BASE_URL + "/login"))
          .check(status().is(200))
          .check(regex("(?s).*?\\\"sessionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraSessionId"))
          .check(regex("(?s).*?\\\"sCtx\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraContext"))
          .check(regex("(?s).*?\\\"sFT\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraFlowToken"))
          .check(regex("(?s).*?\\\"canary\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraCanary"))
          .check(regex("(?s).*?\\\"urlGetCredentialType\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraCredentialTypeUrl"))
          .check(regex("(?s).*?\\\"urlPost\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").saveAs("entraAuthorizePostUrl")))
        .exec(SsoAuthentication::prepareEntraAuthorizeContinuation)
        .exec(SsoAuthentication::normalizeEntraUrls)
        .doIf(SsoAuthentication::needsEntraAuthorizeReload).then(
          // The bootstrap variant needs one GET before it exposes the live login state.
          exec(http("Entra authorize reload").get("#{entraAuthorizeReloadUrl}")
            .headers(Map.of("Accept", BROWSER_HEADERS.get("Accept"), "Accept-Language", BROWSER_HEADERS.get("Accept-Language"), "Upgrade-Insecure-Requests", "1", "Referer", "#{entraAuthorizeUrl}"))
            .transformResponse(DiagnosticLogging.logIfMissingEntraConfiguration("Entra authorize reload"))
            .check(status().is(200)).check(regex("(?s).*?\\\"sessionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").saveAs("entraSessionId"))
            .check(regex("(?s).*?\\\"sCtx\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").saveAs("entraContext"))
            .check(regex("(?s).*?\\\"sFT\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").saveAs("entraFlowToken"))
            .check(regex("(?s).*?\\\"canary\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").saveAs("entraCanary"))
            .check(regex("(?s).*?\\\"urlGetCredentialType\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").saveAs("entraCredentialTypeUrl"))
            .check(regex("(?s).*?\\\"urlPost\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").saveAs("entraLoginUrl"))))
        .exec(SsoAuthentication::normalizeEntraUrls)
        .exec(http("Entra credential discovery").post("#{entraCredentialTypeUrl}")
          .headers(Map.of("Accept", "application/json, text/javascript, */*; q=0.01", "Content-Type", "application/json; charset=UTF-8", "Origin", MICROSOFT_LOGIN_BASE_URL, "Referer", "#{entraAuthorizeUrl}", "canary", "#{entraCanary}", "hpgrequestid", "#{entraSessionId}"))
          .body(StringBody(CREDENTIAL_DISCOVERY_BODY)).asJson().check(status().is(200)))
        // A successful password submit does not always yield KMSI directly. Locally, Entra can
        // respond with a BssoInterrupt page that needs the same credentials form posted again.
        .exec(http("Entra username and password").post("#{entraLoginUrl}")
          .transformResponse(DiagnosticLogging.logIfHtmlContinuationPage("Entra username and password"))
          .headers(Map.of("Accept", BROWSER_HEADERS.get("Accept"), "Content-Type", "application/x-www-form-urlencoded", "Origin", MICROSOFT_LOGIN_BASE_URL, "Referer", "#{entraAuthorizeUrl}"))
          .formParam("i13", "0").formParam("login", "#{username}").formParam("loginfmt", "#{username}").formParam("type", "11").formParam("LoginOptions", "3").formParam("lrt", "").formParam("lrtPartition", "").formParam("hisRegion", "").formParam("hisScaleUnit", "").formParam("passwd", password).formParam("ps", "2").formParam("psRNGCDefaultType", "").formParam("psRNGCEntropy", "").formParam("psRNGCSLK", "").formParam("canary", "#{entraCanary}").formParam("ctx", "#{entraContext}").formParam("hpgrequestid", "#{entraSessionId}").formParam("flowToken", "#{entraFlowToken}").formParam("PPSX", "").formParam("NewUser", "1").formParam("FoundMSAs", "").formParam("fspost", "0").formParam("i21", "0").formParam("CookieDisclosure", "0").formParam("IsFidoSupported", "1").formParam("isSignupPost", "0").formParam("DfpArtifact", "").formParam("i19", "3306")
          .check(status().is(200))
          .check(regex("(?s).*?\\\"sessionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraPostLoginSessionId"))
          .check(regex("(?s).*?\\\"sCtx\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraPostLoginContext"))
          .check(regex("(?s).*?\\\"sFT\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraPostLoginFlowToken"))
          .check(regex("(?s).*?\\\"canary\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraPostLoginCanary"))
          .check(regex("(?s).*?\\\"urlPost\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraPasswordReloadUrl")))
        .exec(SsoAuthentication::normalizeEntraUrls)
        .doIf(SsoAuthentication::needsPostPasswordReload).then(
          // Replay the same password form against Entra's reload endpoint to obtain the
          // post-login continuation tokens needed for KMSI.
          exec(withPasswordForm(http("Entra password reload").post("#{entraPasswordReloadUrl}")
            .transformResponse(DiagnosticLogging.logIfHtmlContinuationPage("Entra password reload"))
            .headers(Map.of("Accept", BROWSER_HEADERS.get("Accept"), "Content-Type", "application/x-www-form-urlencoded", "Origin", MICROSOFT_LOGIN_BASE_URL, "Referer", "#{entraLoginUrl}")), password)
            .check(status().is(200))
            .check(regex("(?s).*?\\\"sessionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraPostLoginSessionId"))
            .check(regex("(?s).*?\\\"sCtx\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraPostLoginContext"))
            .check(regex("(?s).*?\\\"sFT\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraPostLoginFlowToken"))
            .check(regex("(?s).*?\\\"canary\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraPostLoginCanary"))))
        .exec(SsoAuthentication::promotePostLoginContinuation)
        .doIf(SsoAuthentication::hasKmsiContinuation).then(
          // If Entra asks "Stay signed in?", submit the KMSI form. Some runs return a normal
          // redirect, others return another BssoInterrupt HTML page with a urlPost reload.
          exec(withKmsiForm(http("Entra KMSI").post(MICROSOFT_LOGIN_BASE_URL + "/kmsi")
          .transformResponse(DiagnosticLogging.logIfStatusNotIn("Entra KMSI", Set.of(200, 302)))
          .transformResponse(DiagnosticLogging.logIfHtmlContinuationPage("Entra KMSI"))
          .headers(Map.of("Accept", BROWSER_HEADERS.get("Accept"), "Content-Type", "application/x-www-form-urlencoded", "Origin", MICROSOFT_LOGIN_BASE_URL, "Referer", "#{entraLoginUrl}"))
          .disableFollowRedirect()
          .check(status().in(200, 302))
          .check(header("Location").optional().saveAs("entraKmsiRedirectUrl1"))
          .check(regex("(?s).*?\\\"urlPost\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*").optional().saveAs("entraKmsiReloadUrl"))))
          .exec(SsoAuthentication::normalizeEntraUrls)
          .doIf(session -> session.contains("entraKmsiReloadUrl") && !session.contains("entraKmsiRedirectUrl1")).then(
            // The reload POST completes the Entra side of the handshake and yields the callback.
            exec(withKmsiForm(http("Entra KMSI reload").post("#{entraKmsiReloadUrl}")
              .transformResponse(DiagnosticLogging.logIfStatusNotIn("Entra KMSI reload", Set.of(200, 302)))
              .transformResponse(DiagnosticLogging.logIfHtmlContinuationPage("Entra KMSI reload"))
              .headers(Map.of("Accept", BROWSER_HEADERS.get("Accept"), "Content-Type", "application/x-www-form-urlencoded", "Origin", MICROSOFT_LOGIN_BASE_URL, "Referer", MICROSOFT_LOGIN_BASE_URL + "/kmsi"))
              .disableFollowRedirect()
              .check(status().in(200, 302))
              .check(header("Location").optional().saveAs("entraKmsiRedirectUrl1")))))
          .doIf(session -> session.contains("entraKmsiRedirectUrl1")).then(
            // These redirects land back on AppReg's callback and then the authenticated home page.
            exec(http("Entra KMSI Redirect 1").get("#{entraKmsiRedirectUrl1}")
              .transformResponse(DiagnosticLogging.logIfStatusNotIn("Entra KMSI Redirect 1", Set.of(200, 302)))
              .disableFollowRedirect()
              .headers(BROWSER_HEADERS)
              .check(status().in(200, 302))
              .check(header("Location").optional().saveAs("entraKmsiRedirectUrl2"))))
          .doIf(session -> session.contains("entraKmsiRedirectUrl2")).then(
            exec(http("Entra KMSI Redirect 2").get("#{entraKmsiRedirectUrl2}")
              .transformResponse(DiagnosticLogging.logIfStatusNotIn("Entra KMSI Redirect 2", Set.of(200, 302)))
              .disableFollowRedirect()
              .headers(BROWSER_HEADERS)
              .check(status().in(200, 302)))))
        .exec(retryingFinalAppRegGet(
          "AppReg authenticated home",
          http("AppReg authenticated home").get("/").headers(BROWSER_HEADERS)))
        .exec(retryingFinalAppRegGet(
          "AppReg session check",
          http("AppReg session check").get("/sso/me").header("Accept", "application/json"),
          jsonPath("$.authenticated").is("true")))
    );
  }

  /** Retries only the final read-only AppReg validation GETs, never an Entra or callback step. */
  private static ChainBuilder retryingFinalAppRegGet(
      String operation,
      HttpRequestActionBuilder request,
      CheckBuilder... successfulResponseChecks) {
    var attempt = exec(request
        .transformResponse(DiagnosticLogging.logIfStatusAtLeast(operation, 400))
        .check(status().saveAs(FINAL_GET_STATUS_KEY))
        .checkIf((response, session) -> shouldFailRequest(
            response.status().code(), session.getInt(FINAL_GET_ATTEMPT_KEY),
            FINAL_APPREG_GET_RETRIES)).then(status().is(200))
        .checkIf((response, session) -> response.status().code() == 200).then(
          successfulResponseChecks));

    return exec(session -> session
        .set(FINAL_GET_ATTEMPT_KEY, 0)
        .set(FINAL_GET_PENDING_KEY, true))
      .doWhile(session -> session.getBoolean(FINAL_GET_PENDING_KEY)).on(
        exec(attempt)
          .exec(session -> evaluateFinalAppRegGet(operation, session))
          .doIf(session -> session.getBoolean(FINAL_GET_PENDING_KEY)).then(
            pause(FINAL_APPREG_GET_RETRY_DELAY)))
      .exec(session -> session.removeAll(
        FINAL_GET_ATTEMPT_KEY, FINAL_GET_PENDING_KEY, FINAL_GET_STATUS_KEY));
  }

  private static Session evaluateFinalAppRegGet(String operation, Session session) {
    int attempt = session.getInt(FINAL_GET_ATTEMPT_KEY) + 1;
    int statusCode = session.getInt(FINAL_GET_STATUS_KEY);
    if (statusCode == 200) {
      if (attempt > 1 && !session.isFailed()) {
        System.out.printf(
            "APPREG_SSO_GET_RECOVERED timestamp=%s operation=%s attempt=%d "
                + "accountOffset=%s%n",
            Instant.now(), operation, attempt, session.get("accountOffset"));
      }
      return session.set(FINAL_GET_ATTEMPT_KEY, attempt).set(FINAL_GET_PENDING_KEY, false);
    }

    boolean willRetry = shouldRetry(statusCode, attempt, FINAL_APPREG_GET_RETRIES);
    System.out.printf(
        "APPREG_SSO_GET_RETRY timestamp=%s operation=%s status=%d attempt=%d "
            + "maxAttempts=%d retry=%s delaySeconds=%s accountOffset=%s%n",
        Instant.now(), operation, statusCode, attempt, FINAL_APPREG_GET_RETRIES + 1,
        willRetry, willRetry ? FINAL_APPREG_GET_RETRY_DELAY.toSeconds() : "-",
        session.get("accountOffset"));
    return session.set(FINAL_GET_ATTEMPT_KEY, attempt).set(FINAL_GET_PENDING_KEY, willRetry);
  }

  private static Session promotePostLoginContinuation(Session session) {
    // Drop the pre-login state and promote whichever continuation tokens were returned by the
    // latest successful Entra step so downstream requests only read from one set of keys.
    session = session.remove("entraSessionId")
        .remove("entraContext")
        .remove("entraFlowToken")
        .remove("entraCanary");

    if (session.contains("entraPostLoginSessionId")) {
      session = session.set("entraSessionId", session.getString("entraPostLoginSessionId"));
    }
    if (session.contains("entraPostLoginContext")) {
      session = session.set("entraContext", session.getString("entraPostLoginContext"));
    }
    if (session.contains("entraPostLoginFlowToken")) {
      session = session.set("entraFlowToken", session.getString("entraPostLoginFlowToken"));
    }
    if (session.contains("entraPostLoginCanary")) {
      session = session.set("entraCanary", session.getString("entraPostLoginCanary"));
    }

    return session.remove("entraPostLoginSessionId")
        .remove("entraPostLoginContext")
        .remove("entraPostLoginFlowToken")
        .remove("entraPostLoginCanary");
  }

  private static boolean needsPostPasswordReload(Session session) {
    return !hasPostLoginContinuation(session) && session.contains("entraPasswordReloadUrl");
  }

  private static boolean hasKmsiContinuation(Session session) {
    return session.contains("entraSessionId")
      && session.contains("entraContext")
      && session.contains("entraFlowToken")
      && session.contains("entraCanary");
  }

  private static boolean hasPostLoginContinuation(Session session) {
    return session.contains("entraPostLoginSessionId")
      && session.contains("entraPostLoginContext")
      && session.contains("entraPostLoginFlowToken")
      && session.contains("entraPostLoginCanary");
  }

  private static Session prepareEntraAuthorizeContinuation(Session session) {
    String postUrl = session.getString("entraAuthorizePostUrl");
    session = session.remove("entraAuthorizePostUrl");
    if (hasInitialLoginConfiguration(session)) {
      if (!session.contains("entraCredentialTypeUrl")) {
        session = session.set(
            "entraCredentialTypeUrl", MICROSOFT_LOGIN_BASE_URL + "/common/GetCredentialType?mkt=en-GB");
      }
      return session.set("entraLoginUrl", postUrl);
    }
    return session.set("entraAuthorizeReloadUrl", postUrl);
  }

  private static boolean needsEntraAuthorizeReload(Session session) {
    return session.contains("entraAuthorizeReloadUrl");
  }

  private static boolean hasInitialLoginConfiguration(Session session) {
    return session.contains("entraSessionId")
        && session.contains("entraContext")
        && session.contains("entraFlowToken")
        && session.contains("entraCanary");
  }

  private static Session normalizeEntraUrls(Session session) {
    List<String> urlKeys = new ArrayList<>(List.of(
        "entraAuthorizeReloadUrl",
        "entraCredentialTypeUrl",
        "entraLoginUrl",
        "entraPasswordReloadUrl",
        "entraKmsiReloadUrl"));
    for (String key : urlKeys) {
      if (!session.contains(key)) continue;
      session = session.set(key, normalizeEntraUrl(session.getString(key)));
    }
    return session;
  }

  private static String normalizeEntraUrl(String value) {
    String decoded = decodeJavascriptString(value);
    return decoded.startsWith("/") ? MICROSOFT_LOGIN_BASE_URL + decoded : decoded;
  }

  private static String decodeJavascriptString(String value) {
    StringBuilder decoded = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '\\' && index + 1 < value.length()) {
        char next = value.charAt(index + 1);
        if (next == 'u' && index + 5 < value.length()) {
          decoded.append((char) Integer.parseInt(value.substring(index + 2, index + 6), 16));
          index += 5;
          continue;
        }
        if (next == '/') {
          decoded.append('/');
          index++;
          continue;
        }
      }
      decoded.append(current);
    }
    return decoded.toString();
  }

  private static HttpRequestActionBuilder withPasswordForm(
      HttpRequestActionBuilder request, String password) {
    return request
      .formParam("i13", "0")
      .formParam("login", "#{username}")
      .formParam("loginfmt", "#{username}")
      .formParam("type", "11")
      .formParam("LoginOptions", "3")
      .formParam("lrt", "")
      .formParam("lrtPartition", "")
      .formParam("hisRegion", "")
      .formParam("hisScaleUnit", "")
      .formParam("passwd", password)
      .formParam("ps", "2")
      .formParam("psRNGCDefaultType", "")
      .formParam("psRNGCEntropy", "")
      .formParam("psRNGCSLK", "")
      .formParam("canary", "#{entraCanary}")
      .formParam("ctx", "#{entraContext}")
      .formParam("hpgrequestid", "#{entraSessionId}")
      .formParam("flowToken", "#{entraFlowToken}")
      .formParam("PPSX", "")
      .formParam("NewUser", "1")
      .formParam("FoundMSAs", "")
      .formParam("fspost", "0")
      .formParam("i21", "0")
      .formParam("CookieDisclosure", "0")
      .formParam("IsFidoSupported", "1")
      .formParam("isSignupPost", "0")
      .formParam("DfpArtifact", "")
      .formParam("i19", "3306");
  }

  private static HttpRequestActionBuilder withKmsiForm(HttpRequestActionBuilder request) {
    return request
      .formParam("LoginOptions", "3")
      .formParam("type", "28")
      .formParam("ctx", "#{entraContext}")
      .formParam("hpgrequestid", "#{entraSessionId}")
      .formParam("flowToken", "#{entraFlowToken}")
      .formParam("canary", "#{entraCanary}")
      .formParam("i19", "7178");
  }
}
