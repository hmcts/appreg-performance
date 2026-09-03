package utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Retains authenticated AppReg cookie material in memory for round-robin actor assignment. */
public final class AuthenticatedSessionPool {
  private final int capacity;
  private final List<SessionMaterial> sessions = new ArrayList<>();
  private final Set<String> sessionCookieValues = new HashSet<>();
  private int completedAuthenticationJourneys;

  public AuthenticatedSessionPool(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("Session-pool capacity must be positive");
    this.capacity = capacity;
  }

  /** Adds one independently authenticated session without logging either cookie value. */
  public synchronized void add(String sessionCookieValue, String xsrfTokenValue) {
    requireValue("AppReg session cookie", sessionCookieValue);
    requireValue("XSRF token", xsrfTokenValue);
    if (sessions.size() == capacity) {
      throw new IllegalStateException("The authenticated session pool is already full");
    }
    if (!sessionCookieValues.add(sessionCookieValue)) {
      throw new IllegalStateException("The authenticated session pool received a duplicate AppReg session");
    }
    sessions.add(new SessionMaterial(sessionCookieValue, xsrfTokenValue));
  }

  public synchronized boolean ready() {
    return sessions.size() == capacity;
  }

  public synchronized int size() {
    return sessions.size();
  }

  /** Records either a successful or failed configured authentication journey. */
  public synchronized void recordAuthenticationJourneyCompleted() {
    if (completedAuthenticationJourneys == capacity) {
      throw new IllegalStateException("All configured authentication journeys are already complete");
    }
    completedAuthenticationJourneys++;
  }

  public synchronized int completedAuthenticationJourneys() {
    return completedAuthenticationJourneys;
  }

  /** True when completed failures mean the remaining fixed candidates cannot fill the pool. */
  public synchronized boolean authenticationFailed() {
    int remainingAuthenticationJourneys = capacity - completedAuthenticationJourneys;
    return sessions.size() + remainingAuthenticationJourneys < capacity;
  }

  /** Assigns actors round-robin so every pool entry is exercised under concurrent access. */
  public synchronized SessionMaterial sessionForActor(int actorIndex) {
    if (!ready()) throw new IllegalStateException("The authenticated session pool is not ready");
    if (actorIndex < 0) throw new IllegalArgumentException("Actor index must not be negative");
    return sessions.get(actorIndex % capacity);
  }

  static void selfCheck() {
    var pool = new AuthenticatedSessionPool(2);
    if (pool.ready() || pool.size() != 0) {
      throw new IllegalStateException("A new authenticated session pool must be empty");
    }
    pool.add("session-one", "xsrf-one");
    pool.add("session-two", "xsrf-two");
    if (!pool.ready() || pool.size() != 2) {
      throw new IllegalStateException("The authenticated session pool did not reach capacity");
    }
    if (!"session-one".equals(pool.sessionForActor(0).sessionCookieValue())
        || !"session-two".equals(pool.sessionForActor(1).sessionCookieValue())
        || !"session-one".equals(pool.sessionForActor(2).sessionCookieValue())
        || !"xsrf-two".equals(pool.sessionForActor(3).xsrfTokenValue())) {
      throw new IllegalStateException("Authenticated sessions were not assigned round-robin");
    }
    pool.recordAuthenticationJourneyCompleted();
    pool.recordAuthenticationJourneyCompleted();
    if (pool.completedAuthenticationJourneys() != 2 || pool.authenticationFailed()) {
      throw new IllegalStateException("A complete authenticated session pool must not report failure");
    }
    expectInvalid(pool::recordAuthenticationJourneyCompleted);
    expectInvalid(() -> new AuthenticatedSessionPool(0));
    expectInvalid(() -> pool.sessionForActor(-1));
    expectInvalid(() -> pool.add("session-three", "xsrf-three"));

    var duplicatePool = new AuthenticatedSessionPool(2);
    duplicatePool.add("session-one", "xsrf-one");
    expectInvalid(() -> duplicatePool.add("session-one", "xsrf-two"));

    var missingValuePool = new AuthenticatedSessionPool(1);
    expectInvalid(() -> missingValuePool.add("", "xsrf"));
    expectInvalid(() -> missingValuePool.add("session", null));

    var incompletePool = new AuthenticatedSessionPool(2);
    incompletePool.add("session-one", "xsrf-one");
    incompletePool.recordAuthenticationJourneyCompleted();
    if (incompletePool.authenticationFailed()) {
      throw new IllegalStateException("An incomplete candidate population can still fill the pool");
    }
    incompletePool.recordAuthenticationJourneyCompleted();
    if (!incompletePool.authenticationFailed()) {
      throw new IllegalStateException("An exhausted candidate population must fail immediately");
    }

    var failedCandidatePool = new AuthenticatedSessionPool(2);
    failedCandidatePool.recordAuthenticationJourneyCompleted();
    if (!failedCandidatePool.authenticationFailed()) {
      throw new IllegalStateException("A fixed pool must fail as soon as its target is unreachable");
    }
  }

  private static void requireValue(String name, String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is missing");
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
