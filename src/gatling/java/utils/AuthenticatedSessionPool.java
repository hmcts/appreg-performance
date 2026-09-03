package utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Retains authenticated AppReg cookie material in memory for round-robin actor assignment. */
public final class AuthenticatedSessionPool {
  private final int capacity;
  private final int candidateCount;
  private final List<SessionMaterial> sessions = new ArrayList<>();
  private final Set<String> sessionCookieValues = new HashSet<>();
  private int completedAuthenticationJourneys;
  private int failedAuthenticationJourneys;

  public AuthenticatedSessionPool(int capacity, int spareCandidates) {
    if (capacity < 1) throw new IllegalArgumentException("Session-pool capacity must be positive");
    if (spareCandidates < 0) {
      throw new IllegalArgumentException("Spare authentication candidate count must not be negative");
    }
    this.capacity = capacity;
    this.candidateCount = Math.addExact(capacity, spareCandidates);
  }

  /** Records one candidate and atomically retains valid cookie material while the pool needs it. */
  public synchronized boolean completeAuthenticationJourney(
      String sessionCookieValue, String xsrfTokenValue) {
    if (completedAuthenticationJourneys == candidateCount) {
      throw new IllegalStateException("All configured authentication candidates are already complete");
    }
    completedAuthenticationJourneys++;
    if (sessionCookieValue == null || sessionCookieValue.isBlank()
        || xsrfTokenValue == null || xsrfTokenValue.isBlank()
        || sessionCookieValues.contains(sessionCookieValue)) {
      failedAuthenticationJourneys++;
      return false;
    }
    if (ready()) return false;
    sessionCookieValues.add(sessionCookieValue);
    sessions.add(new SessionMaterial(sessionCookieValue, xsrfTokenValue));
    return true;
  }

  public synchronized boolean ready() {
    return sessions.size() == capacity;
  }

  public synchronized int size() {
    return sessions.size();
  }

  public synchronized boolean authenticationRequired() {
    return !ready() && !authenticationFailed();
  }

  public synchronized int completedAuthenticationJourneys() {
    return completedAuthenticationJourneys;
  }

  public synchronized int failedAuthenticationJourneys() {
    return failedAuthenticationJourneys;
  }

  public int candidateCount() {
    return candidateCount;
  }

  /** True when even every remaining candidate succeeding could no longer fill the pool. */
  public synchronized boolean authenticationFailed() {
    int remainingAuthenticationJourneys = candidateCount - completedAuthenticationJourneys;
    return sessions.size() + remainingAuthenticationJourneys < capacity;
  }

  /** Assigns actors round-robin so every pool entry is exercised under concurrent access. */
  public synchronized SessionMaterial sessionForActor(int actorIndex) {
    if (!ready()) throw new IllegalStateException("The authenticated session pool is not ready");
    if (actorIndex < 0) throw new IllegalArgumentException("Actor index must not be negative");
    return sessions.get(actorIndex % capacity);
  }

  static void selfCheck() {
    var pool = new AuthenticatedSessionPool(2, 1);
    if (pool.ready() || pool.size() != 0) {
      throw new IllegalStateException("A new authenticated session pool must be empty");
    }
    pool.completeAuthenticationJourney("session-one", "xsrf-one");
    pool.completeAuthenticationJourney("session-two", "xsrf-two");
    if (!pool.ready() || pool.size() != 2) {
      throw new IllegalStateException("The authenticated session pool did not reach capacity");
    }
    if (!"session-one".equals(pool.sessionForActor(0).sessionCookieValue())
        || !"session-two".equals(pool.sessionForActor(1).sessionCookieValue())
        || !"session-one".equals(pool.sessionForActor(2).sessionCookieValue())
        || !"xsrf-two".equals(pool.sessionForActor(3).xsrfTokenValue())) {
      throw new IllegalStateException("Authenticated sessions were not assigned round-robin");
    }
    if (pool.completedAuthenticationJourneys() != 2 || pool.authenticationFailed()
        || pool.authenticationRequired()) {
      throw new IllegalStateException("A complete authenticated session pool must not report failure");
    }
    if (pool.completeAuthenticationJourney("session-three", "xsrf-three")) {
      throw new IllegalStateException("A surplus successful candidate must not extend a full pool");
    }
    expectInvalid(() -> pool.completeAuthenticationJourney("session-four", "xsrf-four"));
    expectInvalid(() -> new AuthenticatedSessionPool(0, 0));
    expectInvalid(() -> new AuthenticatedSessionPool(1, -1));
    expectInvalid(() -> pool.sessionForActor(-1));

    var recoveredPool = new AuthenticatedSessionPool(2, 1);
    recoveredPool.completeAuthenticationJourney(null, null);
    if (recoveredPool.authenticationFailed() || !recoveredPool.authenticationRequired()) {
      throw new IllegalStateException("A spare candidate must keep the session target reachable");
    }
    recoveredPool.completeAuthenticationJourney("session-one", "xsrf-one");
    recoveredPool.completeAuthenticationJourney("session-two", "xsrf-two");
    if (!recoveredPool.ready() || recoveredPool.failedAuthenticationJourneys() != 1) {
      throw new IllegalStateException("A spare candidate did not recover the session pool");
    }

    var exhaustedPool = new AuthenticatedSessionPool(2, 1);
    exhaustedPool.completeAuthenticationJourney(null, null);
    exhaustedPool.completeAuthenticationJourney(null, null);
    if (!exhaustedPool.authenticationFailed() || exhaustedPool.authenticationRequired()) {
      throw new IllegalStateException("An unreachable session target must fail immediately");
    }
  }

  private static void expectInvalid(Runnable action) {
    boolean rejected = false;
    try {
      action.run();
    } catch (IllegalArgumentException | IllegalStateException expected) {
      rejected = true;
    }
    if (!rejected) throw new IllegalStateException("Expected invalid session-pool use to be rejected");
  }

  /** Deliberately has no value-bearing toString implementation. */
  public static final class SessionMaterial {
    private final String sessionCookieValue;
    private final String xsrfTokenValue;

    private SessionMaterial(String sessionCookieValue, String xsrfTokenValue) {
      this.sessionCookieValue = sessionCookieValue;
      this.xsrfTokenValue = xsrfTokenValue;
    }

    public String sessionCookieValue() {
      return sessionCookieValue;
    }

    public String xsrfTokenValue() {
      return xsrfTokenValue;
    }
  }
}
