package utils;

import io.gatling.http.client.Request;
import io.gatling.javaapi.core.Session;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/** Adds a Gatling-owned W3C parent to AppReg business-operation requests. */
public final class AppRegTraceContext {
  private static final String TRACE_ID_KEY = "appRegTraceId";
  private static final String PHASE_KEY = "appRegTracePhase";
  private static final String ACTION_KEY = "appRegTraceAction";
  private static final String ACTOR_KEY = "appRegTraceActor";
  private static final String TRACEPARENT = "traceparent";
  private static final String APPREG_HOST = URI.create(Environment.BASE_URL).getHost();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final HexFormat HEX = HexFormat.of();

  private AppRegTraceContext() {}

  public static Session startOperation(
      Session session, String phase, String action, int actorIndex) {
    return session
        .set(TRACE_ID_KEY, newIdentifier(16))
        .set(PHASE_KEY, sanitise(phase))
        .set(ACTION_KEY, sanitise(action))
        .set(ACTOR_KEY, actorIndex);
  }

  public static Session endOperation(Session session) {
    return session.removeAll(TRACE_ID_KEY, PHASE_KEY, ACTION_KEY, ACTOR_KEY);
  }

  public static String currentTraceId(Session session) {
    return session.contains(TRACE_ID_KEY) ? session.getString(TRACE_ID_KEY) : "-";
  }

  public static String currentPhase(Session session) {
    return session.contains(PHASE_KEY) ? session.getString(PHASE_KEY) : "-";
  }

  public static String currentAction(Session session) {
    return session.contains(ACTION_KEY) ? session.getString(ACTION_KEY) : "-";
  }

  public static String currentActor(Session session) {
    return session.contains(ACTOR_KEY) ? Integer.toString(session.getInt(ACTOR_KEY)) : "-";
  }

  public static Request signAppRegRequest(Request request, Session session) {
    if (!shouldTrace(request.getUri().getHost(), session.contains(TRACE_ID_KEY))) {
      return request;
    }

    String traceId = session.getString(TRACE_ID_KEY);
    String spanId = newIdentifier(8);
    var signed = request.copyWithCopiedHeaders();
    signed.getHeaders().set(TRACEPARENT, header(traceId, spanId));
    System.out.printf(
        "APPREG_TRACE_CONTEXT timestamp=%s traceId=%s spanId=%s phase=%s action=%s actor=%d request=%s path=%s%n",
        Instant.now(), traceId, spanId, session.getString(PHASE_KEY),
        session.getString(ACTION_KEY), session.getInt(ACTOR_KEY), sanitise(request.getName()),
        sanitisePath(request.getUri().getPath()));
    return signed;
  }

  static String header(String traceId, String spanId) {
    if (!validIdentifier(traceId, 32) || !validIdentifier(spanId, 16)) {
      throw new IllegalArgumentException("W3C trace and span identifiers must be non-zero lowercase hex");
    }
    return "00-" + traceId + "-" + spanId + "-01";
  }

  static boolean shouldTrace(String host, boolean hasOperation) {
    return hasOperation && APPREG_HOST.equalsIgnoreCase(host);
  }

  private static String newIdentifier(int bytes) {
    var value = new byte[bytes];
    do {
      RANDOM.nextBytes(value);
    } while (allZero(value));
    return HEX.formatHex(value);
  }

  private static boolean validIdentifier(String value, int length) {
    return value != null
        && value.length() == length
        && value.matches("[0-9a-f]+")
        && !value.chars().allMatch(character -> character == '0');
  }

  private static boolean allZero(byte[] value) {
    for (byte item : value) {
      if (item != 0) return false;
    }
    return true;
  }

  private static String sanitise(String value) {
    return value == null ? "-" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
  }

  private static String sanitisePath(String value) {
    if (value == null || value.isBlank()) return "/";
    return value.split("\\?", 2)[0].replaceAll("[^A-Za-z0-9_./{}-]", "_");
  }

  public static void main(String[] args) {
    String traceId = newIdentifier(16);
    String firstSpan = newIdentifier(8);
    String secondSpan = newIdentifier(8);
    String firstHeader = header(traceId, firstSpan);
    String secondHeader = header(traceId, secondSpan);
    boolean rejectedInvalid = false;
    try {
      header("0".repeat(32), "short");
    } catch (IllegalArgumentException expected) {
      rejectedInvalid = true;
    }
    if (!firstHeader.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01")
        || !firstHeader.substring(3, 35).equals(secondHeader.substring(3, 35))
        || firstSpan.equals(secondSpan)
        || !shouldTrace(APPREG_HOST, true)
        || shouldTrace("login.microsoftonline.com", true)
        || shouldTrace(APPREG_HOST, false)
        || !rejectedInvalid) {
      throw new IllegalStateException("AppReg W3C trace-context self-check failed");
    }
    System.out.println("AppReg W3C trace-context self-check passed");
  }
}
