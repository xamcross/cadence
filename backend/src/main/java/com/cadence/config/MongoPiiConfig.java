package com.cadence.config;

import com.cadence.domain.AtsConnection;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.Candidate;
import com.cadence.domain.CsvImportFile;
import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.InterestRequest;
import com.cadence.domain.Invitation;
import com.cadence.domain.Member;
import com.cadence.domain.OAuthFlowState;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.security.PiiCrypto;
import com.cadence.security.PiiStringConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

/**
 * Registers the per-property PII converter so {@code Member.email}, {@code Member.displayName} and
 * {@code Invitation.email} are encrypted at rest automatically (research D12). Using programmatic
 * property conversions avoids spreading crypto calls across the service layer.
 */
@Configuration
public class MongoPiiConfig {

    @Bean
    public MongoCustomConversions mongoCustomConversions(PiiCrypto crypto) {
        PiiStringConverter converter = new PiiStringConverter(crypto);
        return MongoCustomConversions.create(adapter ->
            adapter.configurePropertyConversions(registrar -> {
                registrar.registerConverter(Member.class, "email", converter);
                registrar.registerConverter(Member.class, "displayName", converter);
                registrar.registerConverter(Invitation.class, "email", converter);
                // F03: encrypt the email-provider credential at rest (research D2). This is the
                // encryption-at-rest control only; never-return is a SEPARATE control (the field is
                // @JsonIgnore + on no response DTO). Reuse the SAME converter instance; one bean only.
                registrar.registerConverter(WorkspaceConfig.class, "emailProviderCredential", converter);
                // F04: encrypt candidate PII at rest (research D1). Same converter instance; one bean only.
                // emailHash is NOT registered — it is a keyed HMAC, stored as-is for lookup.
                registrar.registerConverter(Candidate.class, "name", converter);
                registrar.registerConverter(Candidate.class, "email", converter);
                registrar.registerConverter(Candidate.class, "phone", converter);
                // F30: encrypt the candidate-visible recruiter free text + the reversible status access token
                // at rest (research D1/D2). statusTokenHash is NOT registered — it is a keyed HMAC, stored as-is
                // for the indexed inbound lookup. Same converter instance; one bean only.
                registrar.registerConverter(Candidate.class, "statusStage", converter);
                registrar.registerConverter(Candidate.class, "statusNextStep", converter);
                registrar.registerConverter(Candidate.class, "statusToken", converter);
                // F01.1: encrypt calendar OAuth secrets + the connected-account id at rest (research D2).
                // Same converter instance; one bean only.
                registrar.registerConverter(CalendarConnection.class, "refreshToken", converter);
                registrar.registerConverter(CalendarConnection.class, "accessToken", converter);
                registrar.registerConverter(CalendarConnection.class, "providerAccountId", converter);
                registrar.registerConverter(OAuthFlowState.class, "pkceVerifier", converter);
                // F13: encrypt the recruiter-provided interview location at rest (research D2). It must
                // survive to the candidate's async confirm; same converter instance; one bean only. The
                // never-return guarantee is a SEPARATE control (@JsonIgnore + on no candidate DTO).
                registrar.registerConverter(SchedulingRequest.class, "locationText", converter);
                // F32: encrypt the submitted scorecard JSON at rest (research D14). It is candidate-assessment
                // PII; one encrypted field, cleared with $set null on candidate erasure (NEVER $unset — the F03
                // ClassCastException trap). Same converter instance; one bean only.
                registrar.registerConverter(FeedbackRequest.class, "scorecardPayload", converter);
                // F32: the scorecard token is stored reversibly-encrypted (the F30 statusToken dual-store) so the
                // escalating reminders can re-send the SAME link; tokenHash (the HMAC) is NOT registered — it is a
                // keyed hash stored as-is for the inbound lookup. Same converter instance; one bean only.
                registrar.registerConverter(FeedbackRequest.class, "token", converter);
                // F40: encrypt the Greenhouse API key (write-only secret, the WorkspaceConfig.emailProviderCredential
                // precedent) and the imported candidate's raw stage label (PII-adjacent free text, FR-022). Both
                // cleared on disconnect/erasure via $set null (NEVER $unset — the F03 ClassCastException trap).
                registrar.registerConverter(AtsConnection.class, "apiKey", converter);
                registrar.registerConverter(Candidate.class, "atsStageLabel", converter);
                // F42: encrypt the raw uploaded CSV bytes (base64 String — the converter is String->String and
                // cannot encrypt a byte[]) and the imported candidate's stage label (PII-adjacent free text).
                // CsvImportFile.dataBase64 is disposed on terminal/TTL; Candidate.importStageLabel is cleared via
                // $set null on erasure (NEVER $unset — the F03 ClassCastException trap). Same converter; one bean.
                registrar.registerConverter(CsvImportFile.class, "dataBase64", converter);
                registrar.registerConverter(Candidate.class, "importStageLabel", converter);
                // F70: encrypt the prospective-member interest submission PII at rest (data-model). emailHash and
                // openEmailHash are NOT registered — they are keyed HMAC values stored as-is for the indexed
                // lookup + dedup. Cleared on erasure with $set "[ERASED]" (NEVER $unset — the F03 ClassCastException
                // trap). Same converter instance; one bean only.
                registrar.registerConverter(InterestRequest.class, "name", converter);
                registrar.registerConverter(InterestRequest.class, "email", converter);
                registrar.registerConverter(InterestRequest.class, "organization", converter);
                registrar.registerConverter(InterestRequest.class, "message", converter);
            }));
    }
}
