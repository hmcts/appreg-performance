package utils;

import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe state for one in-process authentication stage. It assigns a failed primary user's
 * deterministic workload slot to a spare, and releases work only when the requested number of
 * authenticated sessions has been reached.
 */
public final class AuthenticationTargetCoordinator {
  private final int targetUsers;
  private final int maxSpareUsers;
  private final AtomicInteger authenticatedUsers = new AtomicInteger();
  private final AtomicInteger completedPrimaryUsers = new AtomicInteger();
  private final AtomicInteger claimedSpareUsers = new AtomicInteger();
  private final AtomicInteger completedSpareUsers = new AtomicInteger();
  private final AtomicInteger examinedRetryUsers = new AtomicInteger();
  private final AtomicInteger startedRetries = new AtomicInteger();
  private final AtomicInteger completedRetries = new AtomicInteger();
  private final Queue<Integer> spareSlots = new ConcurrentLinkedQueue<>();
  private final Queue<Integer> retrySlots = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean targetFailed = new AtomicBoolean();
  private final AtomicLong recoveryNotBeforeMillis = new AtomicLong();
  private final AtomicLong nextRetryLoginMillis = new AtomicLong();

  public AuthenticationTargetCoordinator(int targetUsers, int maxSpareUsers) {
    if (targetUsers < 1) throw new IllegalArgumentException("Authentication target must be positive");
    if (maxSpareUsers < 0) throw new IllegalArgumentException("Spare account count must not be negative");
    this.targetUsers = targetUsers;
    this.maxSpareUsers = maxSpareUsers;
  }

  /** Records a successful session. Its workload slot may now wait for the common release gate. */
  public void authenticated() {
    int count = authenticatedUsers.incrementAndGet();
    if (count > targetUsers) throw new IllegalStateException("Authenticated more sessions than the target");
  }

  /** A primary login failed before it could consume any workload data. */
  public void primaryCompleted() {
    completedPrimaryUsers.incrementAndGet();
  }

  public boolean primaryPhaseComplete() {
    return completedPrimaryUsers.get() == targetUsers;
  }

  public void primaryFailed(int workloadSlot, int retryAfterSeconds) {
    applyRetryAfter(retryAfterSeconds);
    spareSlots.add(workloadSlot);
  }

  /** A spare takes one failed primary slot only while the session target is still short. */
  public OptionalInt claimSpareSlot() {
    if (targetReached()) return OptionalInt.empty();
    Integer slot = spareSlots.poll();
    if (slot == null) return OptionalInt.empty();
    while (true) {
      int claimed = claimedSpareUsers.get();
      if (claimed >= maxSpareUsers) {
        spareSlots.add(slot);
        return OptionalInt.empty();
      }
      if (claimedSpareUsers.compareAndSet(claimed, claimed + 1)) return OptionalInt.of(slot);
    }
  }

  /** A failed spare returns its primary slot for one final retry. */
  public void spareFailed(int workloadSlot, int retryAfterSeconds) {
    applyRetryAfter(retryAfterSeconds);
    if (!targetReached()) retrySlots.add(workloadSlot);
  }

  public boolean recoveryWaitComplete() {
    return System.currentTimeMillis() >= recoveryNotBeforeMillis.get();
  }

  /** A primary slot can be retried at most once, only while the target remains short. */
  public synchronized boolean claimRetrySlot(int workloadSlot) {
    if (targetReached()) return false;
    if (System.currentTimeMillis() < nextRetryLoginMillis.get()) return false;
    if (!retrySlots.remove(workloadSlot)) return false;
    startedRetries.incrementAndGet();
    nextRetryLoginMillis.set(System.currentTimeMillis() + 1_500L);
    return true;
  }

  public boolean retryPendingFor(int workloadSlot) {
    return retrySlots.contains(workloadSlot);
  }

  public void spareCompleted() {
    completedSpareUsers.incrementAndGet();
  }

  public boolean sparePhaseComplete() {
    return completedSpareUsers.get() == maxSpareUsers;
  }

  public void retryCandidateExamined() { examinedRetryUsers.incrementAndGet(); }

  public void retryCompleted() { completedRetries.incrementAndGet(); }

  /** Called after every retry candidate completion; only the final retry decision may fail the gate. */
  public void evaluateRecoveryResult() {
    if (targetReached()) return;
    if (examinedRetryUsers.get() == targetUsers && completedRetries.get() == startedRetries.get()) {
      targetFailed.set(true);
    }
  }

  public boolean targetReached() {
    return authenticatedUsers.get() == targetUsers;
  }

  public boolean targetFailed() {
    return targetFailed.get();
  }

  public int targetUsers() {
    return targetUsers;
  }

  private void applyRetryAfter(int retryAfterSeconds) {
    if (retryAfterSeconds <= 0) return;
    long notBefore = System.currentTimeMillis() + retryAfterSeconds * 1_000L;
    recoveryNotBeforeMillis.accumulateAndGet(notBefore, Math::max);
  }
}
