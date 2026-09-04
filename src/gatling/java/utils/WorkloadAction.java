package utils;

import java.util.HashSet;

/** Canonical workload keys and their agreed measured-group NFR classifications. */
public enum WorkloadAction {
  UPDATE_APPLICATION("update_application", "AppReg_040_Application_Update", "NFR007"),
  ADD_APPLICATION("add_application", "AppReg_050_Application_Add", "NFR007"),
  RESULT_MULTIPLE("result_multiple", "AppReg_060_Applications_Bulk_Result", "NFR007"),
  UPDATE_RESULT("update_result", "AppReg_070_Application_Result_Update", "NFR007"),
  CREATE_LIST("create_list", "AppReg_020_Application_List_Create", "NFR007"),
  UPDATE_LIST("update_list", "AppReg_080_Application_List_Update", "NFR007"),
  CLOSE_LIST("close_list", "AppReg_090_Application_List_Close", "NFR006"),
  RESULT_APPLICATION("result_application", "AppReg_065_Application_Result", "NFR007"),
  BULK_OFFICIALS("bulk_officials", "AppReg_065_Applications_Bulk_Officials", "NFR007"),
  BULK_FEES("bulk_fees", "AppReg_070_Applications_Bulk_Fees", "NFR007"),
  BULK_UPLOAD("bulk_upload", "AppReg_085_Applications_Bulk_Upload", "NFR007"),
  OTHER_OPERATIONS("other_operations", "AppReg_030_Application_List_Search", "NFR006");

  private final String key;
  private final String groupName;
  private final String nfr;

  WorkloadAction(String key, String groupName, String nfr) {
    this.key = key;
    this.groupName = groupName;
    this.nfr = nfr;
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
    return "NFR006".equals(nfr) ? 2_000 : 5_000;
  }

  /** Small runnable guard against duplicate group paths or inconsistent NFR limits. */
  static void selfCheck() {
    var groupNames = new HashSet<String>();
    for (var action : values()) {
      if (!groupNames.add(action.groupName)) {
        throw new IllegalStateException("Duplicate workload group: " + action.groupName);
      }
    }
  }
}