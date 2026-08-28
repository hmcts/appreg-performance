package utils;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Coordinates a population-wide authentication, measurement and completion window. */
public final class PhaseController {
  public enum Phase {
    AUTHENTICATION_RAMP_UP,
    MEASURED_STEADY_STATE,
    RAMP_DOWN,
    SETUP_FAILED
  }

  private final int targetUsers;
  private final long setupTimeoutNanos;
  private final long steadyStateNanos;
  private final long completionGraceNanos;
  private final LongSupplier nanoTime;

  private Phase phase = Phase.AUTHENTICATION_RAMP_UP;
  private int authenticatedUsers;
  private int completedUsers;
  private int lateCompletions;
  private long setupStartedNanos;
  private long measuredStartedNanos;
  private boolean started;

  public PhaseController(
      int targetUsers,
      Duration setupTimeout,
      Duration steadyStateDuration,
      Duration completionGrace) {
    this(targetUsers, setupTimeout, steadyStateDuration, completionGrace, System::nanoTime);
  }

  PhaseController(
      int targetUsers,
      Duration setupTimeout,
      Duration steadyStateDuration,
      Duration completionGrace,
      LongSupplier nanoTime) {
    if (targetUsers < 1) throw new IllegalArgumentException("Phase target users must be positive");
    if (setupTimeout.isZero() || setupTimeout.isNegative()) {
      throw new IllegalArgumentException("Phase setup timeout must be positive");
    }
    if (steadyStateDuration.isZero() || steadyStateDuration.isNegative()) {
      throw new IllegalArgumentException("Phase steady-state duration must be positive");
    }
    if (completionGrace.isNegative()) {
      throw new IllegalArgumentException("Phase completion grace must not be negative");
    }
    this.targetUsers = targetUsers;
    this.setupTimeoutNanos = setupTimeout.toNanos();
    this.steadyStateNanos = steadyStateDuration.toNanos();
    this.completionGraceNanos = completionGrace.toNanos();
    this.nanoTime = nanoTime;
  }

  public synchronized void start() {
    if (started) throw new IllegalStateException("Phase controller has already started");
    started = true;
    setupStartedNanos = nanoTime.getAsLong();
  }

  /**
   * Registers one retained Gatling session. The single lock is intentional: registration happens
   * once per user, with a ceiling of 500 users; use lock-free state only if that ceiling changes.
   */
  public synchronized Phase registerAuthenticatedSession() {
    requireStarted();
    advanceExpiredPhase();
    if (phase != Phase.AUTHENTICATION_RAMP_UP) return phase;
    authenticatedUsers++;
    if (authenticatedUsers > targetUsers) {
      throw new IllegalStateException("More authenticated sessions were registered than requested");
    }
    if (authenticatedUsers == targetUsers) {
      measuredStartedNanos = nanoTime.getAsLong();
      phase = Phase.MEASURED_STEADY_STATE;
    }
    return phase;
  }

  public synchronized Phase currentPhase() {
    requireStarted();
    advanceExpiredPhase();
    return phase;
  }

  public synchronized int authenticatedUsers() {
    return authenticatedUsers;
  }

  public synchronized boolean sessionCompleted() {
    if (currentPhase() != Phase.RAMP_DOWN) {
      throw new IllegalStateException("Session completed before ramp-down");
    }
    completedUsers++;
    if (completedUsers > authenticatedUsers) {
      throw new IllegalStateException("More sessions completed than authenticated");
    }
    long elapsedAfterSteadyState = nanoTime.getAsLong() - measuredStartedNanos - steadyStateNanos;
    boolean withinGrace = elapsedAfterSteadyState <= completionGraceNanos;
    if (!withinGrace) lateCompletions++;
    return withinGrace;
  }

  public synchronized int completedUsers() {
    return completedUsers;
  }

  public synchronized int lateCompletions() {
    return lateCompletions;
  }

  public synchronized boolean targetReached() {
    return authenticatedUsers == targetUsers
        && (phase == Phase.MEASURED_STEADY_STATE || phase == Phase.RAMP_DOWN);
  }

  private void advanceExpiredPhase() {
    long now = nanoTime.getAsLong();
    if (phase == Phase.AUTHENTICATION_RAMP_UP
        && now - setupStartedNanos >= setupTimeoutNanos) {
      phase = Phase.SETUP_FAILED;
    } else if (phase == Phase.MEASURED_STEADY_STATE
        && now - measuredStartedNanos >= steadyStateNanos) {
      phase = Phase.RAMP_DOWN;
    }
  }

  private void requireStarted() {
    if (!started) throw new IllegalStateException("Phase controller has not started");
  }

  static void selfCheck() {
    var time = new AtomicLong();
    var controller = new PhaseController(
        2, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(5), time::get);
    controller.start();
    require(controller.currentPhase() == Phase.AUTHENTICATION_RAMP_UP, "initial ramp-up phase");
    require(controller.registerAuthenticatedSession() == Phase.AUTHENTICATION_RAMP_UP, "first user ramp-up");
    time.set(Duration.ofSeconds(1).toNanos());
    require(controller.registerAuthenticatedSession() == Phase.MEASURED_STEADY_STATE, "target transition");
    require(controller.targetReached(), "target reached");
    time.addAndGet(Duration.ofSeconds(29).toNanos());
    require(controller.currentPhase() == Phase.MEASURED_STEADY_STATE, "measured window remains open");
    time.addAndGet(Duration.ofSeconds(1).toNanos());
    require(controller.currentPhase() == Phase.RAMP_DOWN, "measured deadline transition");
    require(controller.sessionCompleted(), "first completion within grace");
    require(controller.sessionCompleted(), "second completion within grace");
    require(controller.completedUsers() == 2, "completed session count");

    time.set(0);
    var late = new PhaseController(
        1, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(5), time::get);
    late.start();
    late.registerAuthenticatedSession();
    time.set(Duration.ofSeconds(36).toNanos());
    require(!late.sessionCompleted(), "completion after grace");
    require(late.lateCompletions() == 1, "late completion count");

    time.set(0);
    var failed = new PhaseController(
        2, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ZERO, time::get);
    failed.start();
    failed.registerAuthenticatedSession();
    time.set(Duration.ofSeconds(10).toNanos());
    require(failed.currentPhase() == Phase.SETUP_FAILED, "setup deadline transition");
    require(!failed.targetReached(), "failed setup must not reach target");
  }

  private static void require(boolean condition, String description) {
    if (!condition) throw new IllegalStateException("Phase self-check failed: " + description);
  }
}
