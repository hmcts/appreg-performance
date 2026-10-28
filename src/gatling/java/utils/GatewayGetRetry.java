package utils;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.CheckBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.core.action.builder.SessionHookBuilder;
import io.gatling.commons.validation.Success;
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

/** Bounded recovery of read-only GETs after allowlisted gateway or transport failures. */
public final class GatewayGetRetry {
  private static final String ATTEMPT_KEY = "gatewayGetAttempt";
  private static final String PENDING_KEY = "gatewayGetRetryPending";
  private static final String STATUS_KEY = "gatewayGetStatus";
  private static final String RESPONSE_MILLIS_KEY = "gatewayGetResponseMillis";
  private static final String TRANSPORT_SEEN_KEY = "gatewayGetTransportSeen";
  private static final String GATEWAY_SEEN_KEY = "gatewayGetGatewaySeen";
  private static final String START_NANOS_KEY = "gatewayGetStartNanos";
  private static final String PREVIOUS_FAILED_KEY = "gatewayGetPreviousFailed";
  private static final String DELAY_MILLIS_KEY = "gatewayGetDelayMillis";
  private static final String RETRIES_PROPERTY = "appRegGatewayGetRetries";
  private static final String RETRY_DELAY_SECONDS_PROPERTY = "appRegGatewayGetRetryDelaySeconds";
  private static final int DEFAULT_RETRIES = 3;
  private static final double DEFAULT_RETRY_DELAY_SECONDS = 1.0;
  private static final int MAX_RETRIES = 3;
  private static final double MAX_RETRY_DELAY_SECONDS = 3_600.0;
  private static final AtomicInteger transientFailures = new AtomicInteger();
  private static final AtomicInteger recoveredOperations = new AtomicInteger();
  private static final AtomicInteger exhaustedFailures = new AtomicInteger();
  private static final AtomicInteger transportFailures = new AtomicInteger();
  private static final AtomicInteger transportRecovered = new AtomicInteger();
  private static final AtomicInteger transportExhausted = new AtomicInteger();

  private GatewayGetRetry() {}

  public static ChainBuilder retryingGet(
      String operation,
      HttpRequestActionBuilder request,
      CheckBuilder... successfulResponseChecks) {
    return retryingGetBefore(operation, request, null, successfulResponseChecks);
  }

  /** A poll's original monotonic deadline also bounds scheduling retries, never the job-creation POST. */
  public static ChainBuilder retryingGetBefore(
      String operation,
      HttpRequestActionBuilder request,
      String deadlineSessionKey,
      CheckBuilder... successfulResponseChecks) {
    retries();
    retryDelaySeconds();
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
        .set(TRANSPORT_SEEN_KEY, false)
        .set(GATEWAY_SEEN_KEY, false)
        .set(PENDING_KEY, true))
      .doWhile(session -> session.getBoolean(PENDING_KEY)).on(
        exec(session -> session.removeAll(STATUS_KEY, RESPONSE_MILLIS_KEY, GetTransportFailures.KEY)
            .set(START_NANOS_KEY, System.nanoTime())
            .set(PREVIOUS_FAILED_KEY, session.isFailed()))
          .exec(session -> withinDeadline(session, deadlineSessionKey, 0)
              ? GetTransportFailures.begin(session, request) : session)
          .doIf(session -> session.contains(GetTransportFailures.KEY)).then(
            exec(attempt).exec(new ActionBuilder() {
              @Override
              public SessionHookBuilder asScala() {
                // HttpFailure marks KO before normal Java DSL hooks run. Decide recovery
                // before an enclosing exitBlockOnFail can abort this helper. Exhausted or
                // non-allowlisted failures remain failed and still honour the outer guard.
                return new SessionHookBuilder(scalaSession -> new Success<>(
                    evaluate(operation, new Session(scalaSession), deadlineSessionKey).asScala()), false);
              }
            }))
          .doIf(session -> !session.contains(GetTransportFailures.KEY)).then(
            exec(session -> complete(operation, session, false)))
          .doIf(session -> session.getBoolean(PENDING_KEY)).then(
            pause(session -> Duration.ofMillis(session.getLong(DELAY_MILLIS_KEY)))))
      .exec(session -> session.removeAll(
        ATTEMPT_KEY, PENDING_KEY, STATUS_KEY, RESPONSE_MILLIS_KEY,
        TRANSPORT_SEEN_KEY, GATEWAY_SEEN_KEY,
        START_NANOS_KEY, PREVIOUS_FAILED_KEY, DELAY_MILLIS_KEY, GetTransportFailures.KEY));
  }

  private static Session evaluate(String operation, Session session, String deadlineSessionKey) {
    int attempt = session.getInt(ATTEMPT_KEY) + 1;
    var failureType = GetTransportFailures.finish(session);
    int statusCode = session.contains(STATUS_KEY) ? session.getInt(STATUS_KEY) : 0;
    int responseMillis = session.contains(RESPONSE_MILLIS_KEY)
        ? session.getInt(RESPONSE_MILLIS_KEY) : 0;
    session = session.set(ATTEMPT_KEY, attempt)
        .set(ApplicationListFailureLogger.STATUS_SESSION_KEY, statusCode);
    if (failureType == null && !isTransient(statusCode)) {
      return complete(operation, session, statusCode == 200 && !session.isFailed());
    }

    if (failureType != null) {
      transportFailures.incrementAndGet();
      session = session.set(TRANSPORT_SEEN_KEY, true);
    } else {
      transientFailures.incrementAndGet();
      session = session.set(GATEWAY_SEEN_KEY, true);
    }
    long delayMillis = retryDelay().toMillis();
    boolean willRetry = attempt <= retries() && !session.getBoolean(PREVIOUS_FAILED_KEY)
        && withinDeadline(session, deadlineSessionKey, delayMillis);
    System.out.printf(
        "%s timestamp=%s traceId=%s operation=%s status=%d type=%s "
            + "attempt=%d maxAttempts=%d retry=%s delaySeconds=%s%n",
        failureType == null ? "APPREG_GATEWAY_GET_RETRY" : "APPREG_TRANSPORT_GET_RETRY",
        Instant.now(), AppRegTraceContext.currentTraceId(session), operation, statusCode,
        failureType == null ? "-" : failureType, attempt, retries() + 1, willRetry,
        willRetry ? format(retryDelaySeconds()) : "-");
    if (willRetry) {
      long failedMillis = failureType == null ? responseMillis
          : (System.nanoTime() - session.getLong(START_NANOS_KEY)) / 1_000_000;
      return WorkloadNfrMetrics.excludeGatewayOverhead(
          session.markAsSucceeded().set(PENDING_KEY, true).set(DELAY_MILLIS_KEY, delayMillis),
          Math.addExact(failedMillis, delayMillis));
    }
    return complete(operation, session, false);
  }

  private static Session complete(String operation, Session session, boolean succeeded) {
    if (session.getBoolean(GATEWAY_SEEN_KEY)) {
      (succeeded ? recoveredOperations : exhaustedFailures).incrementAndGet();
    }
    if (session.getBoolean(TRANSPORT_SEEN_KEY)) {
      (succeeded ? transportRecovered : transportExhausted).incrementAndGet();
    }
    if (succeeded && (session.getBoolean(GATEWAY_SEEN_KEY) || session.getBoolean(TRANSPORT_SEEN_KEY))) {
      System.out.printf(
          "%s timestamp=%s traceId=%s operation=%s attempt=%d%n",
          session.getBoolean(TRANSPORT_SEEN_KEY)
              ? "APPREG_TRANSPORT_GET_RECOVERED" : "APPREG_GATEWAY_GET_RECOVERED",
          Instant.now(), AppRegTraceContext.currentTraceId(session), operation, session.getInt(ATTEMPT_KEY));
    }
    session = session.set(PENDING_KEY, false);
    return succeeded ? session : session.markAsFailed();
  }

  private static boolean withinDeadline(Session session, String key, long delayMillis) {
    // In-flight requests still use Gatling's normal HTTP timeout. A tighter wall-clock
    // ceiling would additionally require a per-request timeout based on the remaining budget.
    return key == null || session.getLong(key) - System.nanoTime()
        > Duration.ofMillis(delayMillis).toNanos();
  }

  public static int retries() {
    int value = integerProperty(RETRIES_PROPERTY, DEFAULT_RETRIES);
    if (value < 0 || value > MAX_RETRIES) {
      throw new IllegalArgumentException(RETRIES_PROPERTY + " must be between 0 and " + MAX_RETRIES);
    }
    return value;
  }

  public static int transportFailures() { return transportFailures.get(); }
  public static int transportRecovered() { return transportRecovered.get(); }
  public static int transportExhausted() { return transportExhausted.get(); }

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
    transportFailures.set(0);
    transportRecovered.set(0);
    transportExhausted.set(0);
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
