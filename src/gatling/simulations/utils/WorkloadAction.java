package utils;

import java.util.HashSet;

/** Canonical workload keys and their agreed measured-group NFR classifications. */
public enum WorkloadAction {
  UPDATE_APPLICATION(
      "update_application", "AppReg_040_Application_Update", "NFR006", 2_000),
  ADD_APPLICATION(
      "add_application", "AppReg_050_Application_Add", "NFR006", 2_000),
  RESULT_MULTIPLE(
      "result_multiple", "AppReg_060_Applications_Bulk_Result", "NFR007", 5_000),
  UPDATE_RESULT(
      "update_result", "AppReg_070_Application_Result_Update", "NFR006", 2_000),
  CREATE_LIST(
      "create_list", "AppReg_020_Application_List_Create", "NFR006", 2_000),
  UPDATE_LIST(
      "update_list", "AppReg_080_Application_List_Update", "NFR006", 2_000),
  CLOSE_LIST(
      "close_list", "AppReg_090_Application_List_Close", "NFR006", 2_000),
  RESULT_APPLICATION(
      "result_application", "AppReg_065_Application_Result", "NFR006", 2_000),
  BULK_OFFICIALS(
      "bulk_officials", "AppReg_065_Applications_Bulk_Officials", "NFR007", 5_000),
  BULK_FEES(
      "bulk_fees", "AppReg_070_Applications_Bulk_Fees", "NFR007", 5_000),
  BULK_UPLOAD(
      "bulk_upload", "AppReg_085_Applications_Bulk_Upload", "NFR007", 5_000),
  OTHER_OPERATIONS(
      "other_operations", "AppReg_030_Application_List_Search", "NFR007", 5_000);

  private final String key;
  private final String groupName;
  private final String nfr;
  private final int p95LimitMillis;

  WorkloadAction(String key, String groupName, String nfr, int p95LimitMillis) {
    this.key = key;
    this.groupName = groupName;
    this.nfr = nfr;
    this.p95LimitMillis = p95LimitMillis;
  }

  public String key() {
    return key;
  }

  public String groupName() {
    return groupName;
  }

  public String nfr() {
    return nfr;
  }

  public int p95LimitMillis() {
    return p95LimitMillis;
  }

  /** Small runnable guard against duplicate group paths or inconsistent NFR limits. */
  static void selfCheck() {
    var groupNames = new HashSet<String>();
    for (var action : values()) {
      if (!groupNames.add(action.groupName)) {
        throw new IllegalStateException("Duplicate workload group: " + action.groupName);
      }
      var expectedLimit = "NFR006".equals(action.nfr) ? 2_000 : 5_000;
      if (!("NFR006".equals(action.nfr) || "NFR007".equals(action.nfr))
          || action.p95LimitMillis != expectedLimit) {
        throw new IllegalStateException("Invalid NFR classification for " + action.key);
      }
    }
  }
}
