package utils;

/** Prints deterministic workload action counts for Jenkins feeder trimming. */
public final class WorkloadPlanPrinter {
  private WorkloadPlanPrinter() {}

  public static void main(String[] args) {
    WorkloadProfile profile = WorkloadProfile.fromRuntime();
    for (WorkloadAction action : WorkloadAction.values()) {
      System.out.println(action.key() + "=" + profile.scheduledActionCount(action));
    }
  }
}
