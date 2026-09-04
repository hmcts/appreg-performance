package utils;

/** Prints isolated ramp-up and measured workload action counts for Jenkins feeder construction. */
public final class WorkloadPlanPrinter {
  private WorkloadPlanPrinter() {}

  public static void main(String[] args) {
    WorkloadProfile profile = WorkloadProfile.fromRuntime();
    System.out.println("authentication_candidates=" + profile.authenticationCandidateCount());
    for (WorkloadAction action : WorkloadAction.values()) {
      System.out.println("ramp_up_" + action.key() + "=" + profile.rampScheduledActionCount(action));
      System.out.println("measured_" + action.key() + "=" + profile.scheduledActionCount(action));
    }
  }
}
