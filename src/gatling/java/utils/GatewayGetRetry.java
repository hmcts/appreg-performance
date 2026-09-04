package utils;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.CheckBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.responseTimeInMillis;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.GatewayRetryPolicy.isTransient;
import static utils.GatewayRetryPolicy.shouldFailRequest;
import static utils.GatewayRetryPolicy.shouldRetry;

/** Retries only idempotent GET requests after a gateway 502/504. */
public final class GatewayGetRetry {
  private static final String ATTEMPT_KEY = "gatewayGetAttempt";
  private static final String PENDING_KEY = "gatewayGetRetryPending";
  private static final String STATUS_KEY = "gatewayGetStatus";
  private static final String RESPONSE_MILLIS_KEY = "gatewayGetResponseMillis";
  private static final String RETRIES_PROPERTY = "appRegGatewayGetRetries";
  private static final String RETRY_DELAY_SECONDS_PROPERTY = "appRegGatewayGetRetryDelaySeconds";
  private static final int DEFAULT_RETRIES = 1;
  private static final double DEFAULT_RETRY_DELAY_SECONDS = 1.0;
  private static final int MAX_RETRIES = 5;
  private static final double MAX_RETRY_DELAY_SECONDS = 3_600.0;
  private static final AtomicInteger transientFailures = new AtomicInteger();
  private static final AtomicInteger recoveredOperations = new AtomicInteger();
  private static final AtomicInteger exhaustedFailures = new AtomicInteger();

  private GatewayGetRetry() {}

  public static ChainBuilder retryingGet(
      String operation,
      HttpRequestActionBuilder request,
      CheckBuilder... successfulResponseChecks) {
    var attempt = exec(request
        .transformResponse(DiagnosticLogging.logIfStatusAtLeast(operation, 400))
        .check(status().saveAs(STATUS_KEY))
        .check(responseTimeInMillis().saveAs(RESPONSE_MILLIS_KEY))
        // A recoverable attempt remains support evidence without becoming a logical failure. The
        // final allowed attempt still fails its request check, so exhausted recovery is a KO in
        // Gatling as well as a failed workload action.
        .checkIf((response, session) -> shouldFailRequest(
            response.status().code(), session.getInt(ATTEMPT_KEY), retries())).then(
          status().is(200))
        .checkIf((response, session) -> response.status().code() == 200).then(
          successfulResponseChecks));

    return exec(session -> session
        .set(ATTEMPT_KEY, 0)
        .set(PENDING_KEY, true))
      .doWhile(session -> session.getBoolean(PENDING_KEY)).on(
        exec(attempt)
          .exec(session -> evaluate(operation, session))
          .doIf(session -> session.getBoolean(PENDING_KEY)).then(
            pause(retryDelay())))
      .exec(session -> session.removeAll(
        ATTEMPT_KEY, PENDING_KEY, STATUS_KEY, RESPONSE_MILLIS_KEY));
  }

  private static Session evaluate(String operation, Session session) {
    int attempt = session.getInt(ATTEMPT_KEY) + 1;
    int statusCode = session.getInt(STATUS_KEY);
    int responseMillis = session.getInt(RESPONSE_MILLIS_KEY);
    session = session.set(ApplicationListFailureLogger.STATUS_SESSION_KEY, statusCode);
    if (statusCode == 200) {
      if (session.isFailed()) return session.set(PENDING_KEY, false);
      if (attempt > 1) {
        recoveredOperations.incrementAndGet();
        System.out.printf(
            "APPREG_GATEWAY_GET_RECOVERED timestamp=%s traceId=%s operation=%s "
                + "attempt=%d successfulResponseMillis=%d%n",
            Instant.now(), AppRegTraceContext.currentTraceId(session), operation, attempt,
            responseMillis);
      }
      return session.set(ATTEMPT_KEY, attempt).set(PENDING_KEY, false);
    }

    if (!isTransient(statusCode)) return session.set(PENDING_KEY, false);

    transientFailures.incrementAndGet();
    boolean willRetry = shouldRetry(statusCode, attempt, retries());
    System.out.printf(
        "APPREG_GATEWAY_GET_RETRY timestamp=%s traceId=%s operation=%s status=%d "
            + "attempt=%d maxAttempts=%d retry=%s delaySeconds=%s%n",
        Instant.now(), AppRegTraceContext.currentTraceId(session), operation, statusCode, attempt,
        retries() + 1, willRetry,
        willRetry ? format(retryDelaySeconds()) : "-");
    var updated = session.set(ATTEMPT_KEY, attempt).set(PENDING_KEY, willRetry);
    if (willRetry) {
      long excludedMillis = Math.addExact(responseMillis, retryDelay().toMillis());
      return WorkloadNfrMetrics.excludeGatewayOverhead(updated, excludedMillis);
    }

    exhaustedFailures.incrementAndGet();
    return updated.markAsFailed();
  }

  public static int retries() {
    int value = integerProperty(RETRIES_PROPERTY, DEFAULT_RETRIES);
    if (value < 0 || value > MAX_RETRIES) {
      throw new IllegalArgumentException(RETRIES_PROPERTY + " must be between 0 and " + MAX_RETRIES);
    }
    return value;
  }

  public static double retryDelaySeconds() {
    double value = doubleProperty(RETRY_DELAY_SECONDS_PROPERTY, DEFAULT_RETRY_DELAY_SECONDS);
    if (!Double.isFinite(value) || value < 0 || value > MAX_RETRY_DELAY_SECONDS) {
      throw new IllegalArgumentException(
          RETRY_DELAY_SECONDS_PROPERTY + " must be between 0 and " + (int) MAX_RETRY_DELAY_SECONDS);
    }
    return value;
  }

  public static int transientFailures() {
    return transientFailures.get();
  }

  public static int recoveredOperations() {
    return recoveredOperations.get();
  }

  public static int exhaustedFailures() {
    return exhaustedFailures.get();
  }

  public static void resetCounts() {
    transientFailures.set(0);
    recoveredOperations.set(0);
    exhaustedFailures.set(0);
  }

  private static Duration retryDelay() {
    return Duration.ofMillis((long) Math.ceil(retryDelaySeconds() * 1_000.0));
  }

  private static int integerProperty(String name, int defaultValue) {
    var value = System.getProperty(name);
    if (value == null) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(name + " must be an integer", exception);
    }
  }

  private static double doubleProperty(String name, double defaultValue) {
    var value = System.getProperty(name);
    if (value == null) return defaultValue;
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(name + " must be a number", exception);
    }
  }

  private static String format(double value) {
    return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value);
  }
}
