package utils;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;
import java.time.Duration;
import java.util.OptionalInt;

import static io.gatling.javaapi.core.CoreDsl.asLongAs;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.exitHere;
import static io.gatling.javaapi.core.CoreDsl.pause;

/**
 * Common in-process authentication stage for a simulation. Successful users keep their existing
 * Gatling cookie jar while waiting for the target gate; unsuccessful primary users never proceed
 * into work and make their deterministic slot available to a spare user.
 */
public final class AuthenticationStage {
  private static final String AUTHENTICATED_SESSION_KEY = "authenticationStageAuthenticated";
  private static final String WORKLOAD_SLOT_SESSION_KEY = "authenticationStageWorkloadSlot";
  private static final String SPARE_CLAIMED_SESSION_KEY = "authenticationStageSpareClaimed";
  private static final String RETRY_CLAIMED_SESSION_KEY = "authenticationStageRetryClaimed";
  private static final String RETRY_EXAMINED_SESSION_KEY = "authenticationStageRetryExamined";
  private static final String TARGET_USERS_SESSION_KEY = "authenticationStageTargetUsers";
  private static final String WORKLOAD_RELEASE_DELAY_MILLIS_SESSION_KEY = "authenticationStageWorkloadReleaseDelayMillis";

  private AuthenticationStage() {}

  /**
   * Common framework-proof authentication entry point. Framework proofs deliberately have a
   * one-user target: they validate one canonical data shape and must not start a multi-user job.
   */
  public static ChainBuilder authenticateFrameworkProof() {
    return authenticate(1);
  }

  /**
   * Shared authentication entry point for all runnable modes. Multi-user workload modes compose
   * this HTTP authentication with {@link AuthenticationTargetCoordinator}; a framework proof
   * passes one and remains a single-user operation.
   */
  public static ChainBuilder authenticate(int targetUsers) {
    if (targetUsers < 1) throw new IllegalArgumentException("Authentication target must be positive");
    return exec(session -> session.set(TARGET_USERS_SESSION_KEY, targetUsers))
      .exec(SsoAuthentication.login())
      .exec(session -> session.isFailed()
          ? session
          : session.set(AUTHENTICATED_SESSION_KEY, true));
  }

  public static ChainBuilder registerPrimary(AuthenticationTargetCoordinator coordinator) {
    return exec(session -> registerPrimarySession(session, coordinator));
  }

  /** Waits for a failed primary slot, but never authenticates a spare when no slot is needed. */
  public static ChainBuilder claimSpare(AuthenticationTargetCoordinator coordinator) {
    return asLongAs(session -> !coordinator.recoveryWaitComplete()).on(pause(1))
      .exec(session -> claimSpareSession(session, coordinator))
      .asLongAs(session -> !hasClaimedSpare(session) && !coordinator.primaryPhaseComplete()).on(
        pause(1).exec(session -> claimSpareSession(session, coordinator)));
  }

  public static boolean hasAuthenticatedSession(Session session) {
    return session.contains(AUTHENTICATED_SESSION_KEY)
        && Boolean.TRUE.equals(session.getBoolean(AUTHENTICATED_SESSION_KEY));
  }

  public static boolean hasClaimedSpare(Session session) {
    return Boolean.TRUE.equals(session.getBoolean(SPARE_CLAIMED_SESSION_KEY));
  }

  /** Counts a spare that waited through the primary phase but was not needed. */
  public static ChainBuilder completeUnclaimedSpare(AuthenticationTargetCoordinator coordinator) {
    return doIf(session -> !hasClaimedSpare(session)).then(exec(session -> {
      coordinator.spareCompleted();
      return session;
    }));
  }

  public static ChainBuilder claimRetry(AuthenticationTargetCoordinator coordinator) {
    return asLongAs(session -> !coordinator.sparePhaseComplete()).on(pause(1))
      .asLongAs(session -> !coordinator.recoveryWaitComplete()).on(pause(1))
      .exec(session -> claimRetrySession(session, coordinator))
      .asLongAs(session -> shouldWaitForRetry(session, coordinator)).on(
        pause(1).exec(session -> claimRetrySession(session, coordinator)));
  }

  public static boolean hasClaimedRetry(Session session) {
    return Boolean.TRUE.equals(session.getBoolean(RETRY_CLAIMED_SESSION_KEY));
  }

  public static ChainBuilder registerSpare(AuthenticationTargetCoordinator coordinator) {
    return exec(session -> {
      if (session.isFailed()) {
        Integer slot = session.getInt(WORKLOAD_SLOT_SESSION_KEY);
        if (slot != null) coordinator.spareFailed(slot, retryAfterSeconds(session));
        coordinator.spareCompleted();
        return session.markAsSucceeded().set(AUTHENTICATED_SESSION_KEY, false);
      }
      coordinator.authenticated();
      coordinator.spareCompleted();
      return session.set("accountOffset", session.getInt(WORKLOAD_SLOT_SESSION_KEY))
          .set(AUTHENTICATED_SESSION_KEY, true);
    });
  }

  public static ChainBuilder registerRetry(AuthenticationTargetCoordinator coordinator) {
    return exec(session -> {
      coordinator.retryCompleted();
      if (session.isFailed()) {
        coordinator.evaluateRecoveryResult();
        return session.markAsSucceeded().set(AUTHENTICATED_SESSION_KEY, false);
      }
      coordinator.authenticated();
      coordinator.evaluateRecoveryResult();
      return session.set(AUTHENTICATED_SESSION_KEY, true);
    });
  }

  /** Holds successful sessions idle until the target is reached; failed sessions leave cleanly. */
  public static ChainBuilder awaitTarget(AuthenticationTargetCoordinator coordinator) {
    return asLongAs(session -> !coordinator.targetReached() && !coordinator.targetFailed()).on(
        pause(1))
      .doIf(session -> !hasAuthenticatedSession(session)).then(exitHere())
      .doIf(session -> coordinator.targetFailed()).then(exec(session -> session.markAsFailed()));
  }

  /** Releases retained sessions at the selected cadence after the authentication target opens. */
  public static ChainBuilder staggerWorkloadRelease(
      AuthenticationTargetCoordinator coordinator, double intervalSeconds) {
    return exec(session -> {
      if (!hasAuthenticatedSession(session)) return session;
      int slot = session.getInt("accountOffset");
      long delayMillis = Math.round(slot * intervalSeconds * 1_000D);
      return session.set(WORKLOAD_RELEASE_DELAY_MILLIS_SESSION_KEY, delayMillis);
    }).pause(session -> {
      if (!session.contains(WORKLOAD_RELEASE_DELAY_MILLIS_SESSION_KEY)) return Duration.ZERO;
      Long delayMillis = session.getLong(WORKLOAD_RELEASE_DELAY_MILLIS_SESSION_KEY);
      return Duration.ofMillis(delayMillis == null ? 0 : delayMillis);
    });
  }

  private static Session registerPrimarySession(Session session, AuthenticationTargetCoordinator coordinator) {
    coordinator.primaryCompleted();
    if (session.isFailed()) {
      Integer slot = session.getInt("accountOffset");
      if (slot == null) throw new IllegalStateException("Primary authentication is missing accountOffset");
      coordinator.primaryFailed(slot, retryAfterSeconds(session));
      return session.markAsSucceeded().set(AUTHENTICATED_SESSION_KEY, false);
    }
    coordinator.authenticated();
    return session.set(AUTHENTICATED_SESSION_KEY, true);
  }

  private static Session claimSpareSession(Session session, AuthenticationTargetCoordinator coordinator) {
    OptionalInt slot = coordinator.claimSpareSlot();
    return slot.isPresent()
        ? session.set(WORKLOAD_SLOT_SESSION_KEY, slot.getAsInt()).set(SPARE_CLAIMED_SESSION_KEY, true)
        : session.set(SPARE_CLAIMED_SESSION_KEY, false);
  }

  private static Session claimRetrySession(Session session, AuthenticationTargetCoordinator coordinator) {
    Integer slot = session.getInt("accountOffset");
    boolean retryThisAccount = slot != null && coordinator.claimRetrySlot(slot);
    if (retryThisAccount || (!retryThisAccount && (slot == null || !coordinator.retryPendingFor(slot)))) {
      coordinator.retryCandidateExamined();
      coordinator.evaluateRecoveryResult();
      session = session.set(RETRY_EXAMINED_SESSION_KEY, true);
    }
    return session.set(RETRY_CLAIMED_SESSION_KEY, retryThisAccount);
  }

  private static boolean shouldWaitForRetry(Session session, AuthenticationTargetCoordinator coordinator) {
    Integer slot = session.getInt("accountOffset");
    return !hasClaimedRetry(session)
        && !coordinator.targetReached()
        && slot != null
        && coordinator.retryPendingFor(slot);
  }

  private static int retryAfterSeconds(Session session) {
    String retryAfter = session.getString("retryAfter");
    if (retryAfter == null) return 0;
    try {
      return Math.max(0, Integer.parseInt(retryAfter));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }
}
