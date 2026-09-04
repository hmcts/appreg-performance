package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import utils.Headers;
import utils.WorkloadAction;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.exitBlockOnFail;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.ApplicationListFailureLogger.STATUS_SESSION_KEY;
import static utils.ApplicationListFailureLogger.logFailure;
import static utils.GatewayGetRetry.retryingGet;

/**
 * Updates the Application List and entry identifiers stored in the Gatling session.
 * This chain assumes the caller has authenticated, set {@code applicationListId} and
 * {@code applicationEntryId}, and holds an AppReg anti-forgery cookie.
 */
public final class UpdateApplicationScenario {
  private UpdateApplicationScenario() {}

  public static ChainBuilder updateApplication() {
    return group(WorkloadAction.UPDATE_APPLICATION.groupName()).on(
      exec(session -> {
        String updateId = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        return session
          .set("applicationUpdateId", updateId)
          .set("applicationUpdateDate", LocalDate.now().toString());
      })
        .exec(getCookieValue(CookieKey(Headers.XSRF_TOKEN_COOKIE).saveAs("xsrfToken")))
        .exec(exitBlockOnFail().on(
          exec(retryingGet(
            "Get application list",
            http("Get application list")
              .get("/application-lists/#{applicationListId}")
              .queryParam("pageNumber", "0")
              .queryParam("pageSize", "10")
              .header("Accept", Headers.APPREG_API_MEDIA_TYPE),
            status().saveAs(STATUS_SESSION_KEY)))
          .exec(logFailure("Update Application", "Get application list"))
          .exec(retryingGet(
            "Get application entry",
            http("Get application entry")
              .get("/application-lists/#{applicationListId}/entries/#{applicationEntryId}")
              .header("Accept", Headers.APPREG_API_MEDIA_TYPE),
            jsonPath("$.applicationCode").exists().saveAs("applicationCode")))
          .exec(retryingGet(
            "Get application-code details",
            http("Get application-code details")
              .get("/application-codes/#{applicationCode}")
              .queryParam("date", "#{applicationUpdateDate}")
              .header("Accept", Headers.APPREG_API_MEDIA_TYPE)))
          .exec(http("Update application")
            .put("/application-lists/#{applicationListId}/entries/#{applicationEntryId}")
            .header("Accept", Headers.APPREG_API_MEDIA_TYPE)
            .header("Content-Type", Headers.APPREG_API_MEDIA_TYPE)
            .header(Headers.XSRF_TOKEN_HEADER, "#{xsrfToken}")
            .body(StringBody("""
              {"applicationCode":"#{applicationCode}","applicant":{"person":{"name":{"firstName":"Gatling","middleName":"Update","lastName":"Applicant #{applicationUpdateId}"},"contactDetails":{"addressLine1":"23 Performance Road","addressLine3":"Liverpool","addressLine4":"England","addressLine5":"Swansea","postcode":"SW1A 2AA","phone":"020 7946 0000","mobile":"07854 555555","email":"updated#{applicationUpdateId}@example.com"}}},"respondent":{"person":{"name":{"firstName":"Gatling","middleName":"Update","lastName":"Respondent #{applicationUpdateId}"},"dateOfBirth":"1970-01-01","contactDetails":{"addressLine1":"The House","addressLine2":"31 Test Close","addressLine3":"Galway","addressLine4":"Shropshire","addressLine5":"Tinmus","postcode":"SW1B 2BB","phone":"020 7946 0000","mobile":"07123 456789","email":"updated-respondent#{applicationUpdateId}@example.com"}}},"numberOfRespondents":null,"wordingFields":[],"feeStatuses":[{"paymentReference":"UPD-#{applicationUpdateId}","paymentStatus":"PAID","statusDate":"#{applicationUpdateDate}"}],"hasOffsiteFee":true,"caseReference":"UPD-#{applicationUpdateId}","accountNumber":"UPD-#{applicationUpdateId}","notes":"Gatling update #{applicationUpdateId}","officials":[]}
              """))
            .check(status().is(200)))))
    );
  }
}
