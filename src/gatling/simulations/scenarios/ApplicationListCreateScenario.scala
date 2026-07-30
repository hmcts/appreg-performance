package scenarios

import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder

/**
  * Template for ARCPOC-1597.
  *
  * Keep this separate from the authenticated applications-list smoke journey
  * until the recorded browser flow has been reviewed. The recording will
  * determine the real route names, dynamic values, test data and assertions.
  */
object ApplicationListCreateScenario {

  def createApplicationList: ChainBuilder =
    group("AppReg_020_Application_List_Create") {
      // Recording review checklist:
      // 1. Retain only journey requests; Gatling infers static browser assets.
      // 2. Capture dynamic IDs, CSRF values and any generated form state.
      // 3. Feed unique, non-production create data per virtual user.
      // 4. Assert successful creation using the real confirmation/data response.
      // 5. Identify asynchronous processing and downstream dependencies.
      exec(session => session)
    }
}
