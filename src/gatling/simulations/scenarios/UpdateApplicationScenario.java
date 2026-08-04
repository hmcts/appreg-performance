package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.LocalDate;
import java.util.UUID;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static utils.Headers.COMMON_HEADER;

/**
 * Updates one approved, isolated Application entry using disposable values.
 * This chain assumes the caller has already authenticated using {@code SsoAuthentication}.
 */
public final class UpdateApplicationScenario {
  private static final String APPLICATION_LIST_ID = requiredProperty("appRegUpdateApplicationListId");
  private static final String APPLICATION_ENTRY_ID = requiredProperty("appRegUpdateApplicationEntryId");
  private static final String APPLICATION_CODE = System.getProperty("appRegUpdateApplicationCode", "MX99006");
  private static final String WORDING_FIELD_KEY = System.getProperty(
    "appRegUpdateApplicationWordingFieldKey",
    "Describe Seized Food"
  );

  private UpdateApplicationScenario() {}

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Set JVM property: " + name);
    }
    return value;
  }

  public static ChainBuilder updateApplication() {
    return group("AppReg_040_Application_Update").on(
      exec(session -> {
        String updateId = UUID.randomUUID().toString();
        return session
          .set("applicationUpdateId", updateId)
          .set("applicationUpdateDate", LocalDate.now().toString());
      })
        .exec(http("Application lists page")
          .get("/applications-list")
          .headers(COMMON_HEADER)
          .check(status().is(200)))
        .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("xsrfToken")))
        .exec(http("Get application list")
          .get("/application-lists/" + APPLICATION_LIST_ID)
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "10")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Get application entries")
          .get("/application-lists/" + APPLICATION_LIST_ID + "/entries")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "10")
          .queryParam("sort", "sequenceNumber,asc")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Get application entry")
          .get("/application-lists/" + APPLICATION_LIST_ID + "/entries/" + APPLICATION_ENTRY_ID)
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Get application-code details")
          .get("/application-codes/" + APPLICATION_CODE)
          .queryParam("date", "#{applicationUpdateDate}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Update application")
          .put("/application-lists/" + APPLICATION_LIST_ID + "/entries/" + APPLICATION_ENTRY_ID)
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody("""
            {"applicationCode":"%s","applicant":{"person":{"name":{"title":"mr","firstName":"Gatling","middleName":"Proof","lastName":"#{applicationUpdateId}"},"contactDetails":{"addressLine1":"1 Gatling Way","addressLine2":"Performance Test","addressLine3":"Test City","addressLine5":"Test County","postcode":"TE1 1ST","phone":"01632960001","mobile":"07700900001","email":"gatling-#{applicationUpdateId}@example.invalid"}}},"respondent":{"person":{"name":{"title":"other","firstName":"Gatling","lastName":"Respondent #{applicationUpdateId}"},"dateOfBirth":"2001-08-04","contactDetails":{"addressLine1":"2 Gatling Way","addressLine2":"Performance Test","postcode":"TE1 1ST","phone":"01632960002","mobile":"07700900002","email":"respondent-#{applicationUpdateId}@example.invalid"}}},"numberOfRespondents":null,"wordingFields":[{"key":"%s","value":"Gatling update proof #{applicationUpdateId}"}],"feeStatuses":[{"paymentReference":null,"paymentStatus":"DUE","statusDate":"#{applicationUpdateDate}"}],"hasOffsiteFee":false,"caseReference":"GATLING-#{applicationUpdateId}","accountNumber":"GATLING-#{applicationUpdateId}","notes":"Gatling update proof #{applicationUpdateId}","officials":[]}
            """.formatted(APPLICATION_CODE, WORDING_FIELD_KEY)))
          .check(status().is(200)))
    );
  }
}
