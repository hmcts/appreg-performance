package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.Headers.COMMON_HEADER;

/**
 * Finds one Application entry in a Bulk list, then updates it using disposable values.
 * This chain assumes the caller has already authenticated using {@code SsoAuthentication}.
 */
public final class UpdateApplicationScenario {
  private static final String WORDING_FIELD_KEY = System.getProperty(
    "appRegUpdateApplicationWordingFieldKey",
    "Describe Seized Food"
  );

  private UpdateApplicationScenario() {}

  public static ChainBuilder updateApplication() {
    return group("AppReg_040_Application_Update").on(
      exec(session -> {
        String updateId = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        return session
          .set("applicationUpdateId", updateId)
          .set("applicationUpdateDate", LocalDate.now().toString());
      })
        .exec(http("Application lists page")
          .get("/applications-list")
          .headers(COMMON_HEADER)
          .check(status().is(200)))
        .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("xsrfToken")))
        .exec(SearchScenario.searchApplicationLists())
        .exec(http("Get application list")
          .get("/application-lists/#{applicationListId}")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "10")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Get application entries")
          .get("/application-lists/#{applicationListId}/entries")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "10")
          .queryParam("sort", "sequenceNumber,asc")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200))
          .check(jsonPath("$.content[0].id").saveAs("applicationEntryId")))
        .exec(http("Get application entry")
          .get("/application-lists/#{applicationListId}/entries/#{applicationEntryId}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200))
          .check(jsonPath("$.applicationCode").saveAs("applicationCode")))
        .exec(http("Get application-code details")
          .get("/application-codes/#{applicationCode}")
          .queryParam("date", "#{applicationUpdateDate}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Update application")
          .put("/application-lists/#{applicationListId}/entries/#{applicationEntryId}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody("""
            {"applicationCode":"#{applicationCode}","applicant":{"person":{"name":{"title":"mr","firstName":"Gatling","middleName":"Proof","lastName":"Taylor #{applicationUpdateId}"},"contactDetails":{"addressLine1":"1 Gatling Way","addressLine2":"Performance Test","addressLine3":"Test City","addressLine5":"Test County","postcode":"SW1A 1AA","phone":"01632960001","mobile":"07700900001","email":"gatling#{applicationUpdateId}@example.com"}}},"respondent":{"person":{"name":{"title":"other","firstName":"Gatling","lastName":"Clark #{applicationUpdateId}"},"dateOfBirth":"2001-08-04","contactDetails":{"addressLine1":"2 Gatling Way","addressLine2":"Performance Test","postcode":"SW1A 1AA","phone":"01632960002","mobile":"07700900002","email":"respondent#{applicationUpdateId}@example.com"}}},"numberOfRespondents":null,"wordingFields":[{"key":"%s","value":"Gatling update #{applicationUpdateId}"}],"feeStatuses":[{"paymentReference":null,"paymentStatus":"DUE","statusDate":"#{applicationUpdateDate}"}],"hasOffsiteFee":false,"caseReference":"CASE-#{applicationUpdateId}","accountNumber":"ACC-FEE-#{applicationUpdateId}","notes":"Gatling update #{applicationUpdateId}","officials":[]}
            """.formatted(WORDING_FIELD_KEY)))
          .check(status().is(200)))
    );
  }
}
