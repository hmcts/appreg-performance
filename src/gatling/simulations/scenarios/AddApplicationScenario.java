package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Adds and completes an Application in the Application List stored in the Gatling session.
 * This chain assumes the caller has authenticated and set {@code applicationListId}.
 */
public final class AddApplicationScenario {
  private static final String APPLICATION_CODE = System.getProperty("appRegAddApplicationCode", "AD99001");
  private static final String APPLICATION_TITLE = System.getProperty("appRegAddApplicationTitle", "Copy documents");

  private AddApplicationScenario() {}

  public static ChainBuilder addApplication() {
    return addApplication("applicationEntryId");
  }

  /**
   * Adds and completes an Application, saving its ID under the supplied Gatling session key.
   * This allows a composed business flow to operate on more than one isolated Application.
   */
  public static ChainBuilder addApplication(String entryIdSessionKey) {
    return group("AppReg_050_Application_Add").on(
      exec(session -> session
        .set("applicationAddId", String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000)))
        .set("applicationAddDate", LocalDate.now().toString()))
        .exec(http("Search application codes")
          .get("/application-codes")
          .queryParam("code", APPLICATION_CODE)
          .queryParam("title", APPLICATION_TITLE)
          .queryParam("date", "#{applicationAddDate}")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "10")
          .queryParam("sort", "code,asc")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Get application-code details")
          .get("/application-codes/" + APPLICATION_CODE)
          .queryParam("date", "#{applicationAddDate}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Add application")
          .post("/application-lists/#{applicationListId}/entries")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody(applicationBody()))
          .check(status().in(200, 201))
          .check(jsonPath("$.id").saveAs(entryIdSessionKey)))
        .exec(http("Get added application")
          .get("/application-lists/#{applicationListId}/entries/#{" + entryIdSessionKey + "}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Get result codes")
          .get("/result-codes")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "100")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Get application results")
          .get("/application-lists/#{applicationListId}/entries/#{" + entryIdSessionKey + "}/results")
          .queryParam("pageNumber", "0")
          .queryParam("pageSize", "100")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .check(status().is(200)))
        .exec(http("Complete application")
          .put("/application-lists/#{applicationListId}/entries/#{" + entryIdSessionKey + "}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody(completedApplicationBody()))
          .check(status().is(200)))
    );
  }

  private static String applicationBody() {
    return """
      {"applicationCode":"%s","feeStatuses":[{"paymentStatus":"PAID","statusDate":"#{applicationAddDate}","paymentReference":"PAY-#{applicationAddId}"}],"hasOffsiteFee":true,"lodgementDate":"#{applicationAddDate}","caseReference":"CR-#{applicationAddId}","accountNumber":"ACC-#{applicationAddId}","notes":"Gatling add #{applicationAddId}","applicant":{"person":{"name":{"firstName":"Gatling","middleName":"Proof","lastName":"Applicant #{applicationAddId}"},"contactDetails":{"addressLine1":"23 Performance Road","addressLine3":"Liverpool","addressLine4":"England","addressLine5":"Swansea","postcode":"SW1A 2AA","phone":"020 7946 0000","mobile":"07854 555555","email":"applicant#{applicationAddId}@example.com"}}},"respondent":{"person":{"name":{"firstName":"Gatling","middleName":"Proof","lastName":"Respondent #{applicationAddId}"},"dateOfBirth":"1970-01-01","contactDetails":{"addressLine1":"The House","addressLine2":"31 Test Close","addressLine3":"Galway","addressLine4":"Shropshire","addressLine5":"Tinmus","postcode":"SW1B 2BB","phone":"020 7946 0000","mobile":"07123 456789","email":"respondent#{applicationAddId}@example.com"}}},"numberOfRespondents":null}
      """.formatted(APPLICATION_CODE);
  }

  private static String completedApplicationBody() {
    return """
      {"applicationCode":"%s","applicant":{"person":{"name":{"firstName":"Gatling","middleName":"Proof","lastName":"Applicant #{applicationAddId}"},"contactDetails":{"addressLine1":"23 Performance Road","addressLine3":"Liverpool","addressLine4":"England","addressLine5":"Swansea","postcode":"SW1A 2AA","phone":"020 7946 0000","mobile":"07854 555555","email":"applicant#{applicationAddId}@example.com"}}},"respondent":{"person":{"name":{"firstName":"Gatling","middleName":"Proof","lastName":"Respondent #{applicationAddId}"},"dateOfBirth":"1970-01-01","contactDetails":{"addressLine1":"The House","addressLine2":"31 Test Close","addressLine3":"Galway","addressLine4":"Shropshire","addressLine5":"Tinmus","postcode":"SW1B 2BB","phone":"020 7946 0000","mobile":"07123 456789","email":"respondent#{applicationAddId}@example.com"}}},"numberOfRespondents":null,"wordingFields":[],"feeStatuses":[{"paymentReference":"PAY-#{applicationAddId}","paymentStatus":"PAID","statusDate":"#{applicationAddDate}"}],"hasOffsiteFee":true,"caseReference":"CR-#{applicationAddId}","accountNumber":"ACC-#{applicationAddId}","notes":"Gatling add #{applicationAddId}","officials":[]}
      """.formatted(APPLICATION_CODE);
  }
}
