package scenarios;

import io.gatling.javaapi.core.ChainBuilder;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/** Builds the entry-level conditions required to close an Application List for proof-only setup. */
public final class CloseReadyApplicationListSeedScenario {
  private CloseReadyApplicationListSeedScenario() {}

  public static ChainBuilder makeListCloseReady() {
    return group("AppReg_075_Application_List_Close_Seed").on(
      exec(session -> session
        .set("closeReadyId", String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000)))
        .set("closeReadyDate", LocalDate.now().toString()))
        .exec(http("Add official to close-ready application")
          .put("/application-lists/#{applicationListId}/entries/#{applicationEntryId}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody(entryBody()))
          .check(status().is(200)))
        .exec(http("Add close-ready application result")
          .post("/application-lists/#{applicationListId}/entries/#{applicationEntryId}/results")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody("{" + "\"resultCode\":\"AUTH\",\"wordingFields\":[]" + "}"))
          .check(status().in(200, 201)))
        .exec(http("Finalise close-ready application")
          .put("/application-lists/#{applicationListId}/entries/#{applicationEntryId}")
          .header("Accept", "application/vnd.hmcts.appreg.v1+json")
          .header("Content-Type", "application/vnd.hmcts.appreg.v1+json")
          .header("X-XSRF-TOKEN", "#{xsrfToken}")
          .body(StringBody(entryBody()))
          .check(status().is(200)))
    );
  }

  private static String entryBody() {
    return """
      {"applicationCode":"#{applicationCode}","applicant":{"person":{"name":{"firstName":"Gatling","middleName":"Close","lastName":"Applicant #{closeReadyId}"},"contactDetails":{"addressLine1":"23 Performance Road","addressLine3":"Liverpool","addressLine4":"England","addressLine5":"Swansea","postcode":"SW1A 2AA","phone":"020 7946 0000","mobile":"07854 555555","email":"close-ready#{closeReadyId}@example.com"}}},"respondent":{"person":{"name":{"firstName":"Gatling","middleName":"Close","lastName":"Respondent #{closeReadyId}"},"dateOfBirth":"1970-01-01","contactDetails":{"addressLine1":"The House","addressLine2":"31 Test Close","addressLine3":"Galway","addressLine4":"Shropshire","addressLine5":"Tinmus","postcode":"SW1B 2BB","phone":"020 7946 0000","mobile":"07123 456789","email":"close-ready-respondent#{closeReadyId}@example.com"}}},"numberOfRespondents":null,"wordingFields":[],"feeStatuses":[{"paymentReference":"CLOSE-#{closeReadyId}","paymentStatus":"PAID","statusDate":"#{closeReadyDate}"}],"hasOffsiteFee":true,"caseReference":"CLOSE-#{closeReadyId}","accountNumber":"CLOSE-#{closeReadyId}","notes":"Gatling close-ready #{closeReadyId}","officials":[{"type":"MAGISTRATE","title":"Mr","forename":"Big","surname":"Wig"}]}
      """;
  }
}
