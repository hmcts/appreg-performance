package scenarios;

import io.gatling.javaapi.core.ChainBuilder;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;

/** Template for ARCPOC-1597. */
public final class ApplicationListCreateScenario {
  private ApplicationListCreateScenario() {}

  public static final ChainBuilder CREATE_APPLICATION_LIST = group("AppReg_020_Application_List_Create").on(
    // Recording review: retain journey requests, capture dynamic data and assert creation.
    exec(session -> session)
  );
}
