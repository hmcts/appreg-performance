package utils;

/** Canonical action keys used by the deterministic workload plan and its feeder allocations. */
public enum WorkloadAction {
  UPDATE_APPLICATION("update_application"),
  ADD_APPLICATION("add_application"),
  RESULT_MULTIPLE("result_multiple"),
  UPDATE_RESULT("update_result"),
  CREATE_LIST("create_list"),
  UPDATE_LIST("update_list"),
  CLOSE_LIST("close_list"),
  RESULT_APPLICATION("result_application"),
  BULK_OFFICIALS("bulk_officials"),
  BULK_FEES("bulk_fees"),
  BULK_UPLOAD("bulk_upload"),
  OTHER_OPERATIONS("other_operations");

  private final String key;

  WorkloadAction(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
