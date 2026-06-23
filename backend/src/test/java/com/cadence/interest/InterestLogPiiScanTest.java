package com.cadence.interest;

import com.cadence.domain.RecruiterNotification;
import com.cadence.service.InterestRequestService.SubmitCommand;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T037/FR-009/SC-010 (persisted-artefact leg; the CI log-grep is the captured-stdout backstop): the submitter PII
 * sentinels (name/email/org/message) never appear in the persisted {@code interestRequests} docs (only ciphertext),
 * the {@code recruiterNotifications} row, nor the {@code deadLetterRecords} collection — across submit + a
 * forced-failure notify path. A failure cause is reduced to a PII-free cause-class string at the service boundary
 * (the F22 lesson), so even a thrown notification never carries the PII into the dead-letter / log.
 */
class InterestLogPiiScanTest extends InterestItBase {

    private static final String NAME = "SENTINELF70NAME_zz9";
    private static final String EMAIL = "sentinelf70email_zz9@dont.log";
    private static final String ORG = "SENTINELF70ORG_zz9";
    private static final String MSG = "SENTINELF70MSG_zz9";

    @SpyBean
    com.cadence.service.RecruiterNotificationService notifications;

    @Test
    void noSubmitterPii_inPersistedArtefacts_evenOnNotifyFailure() {
        // Force the notify side effect to throw — the service swallows it best-effort with a PII-free summary.
        Mockito.doThrow(new RuntimeException("notify boom " + NAME))
            .when(notifications).notify(Mockito.eq(WS), Mockito.isNull(), Mockito.any());

        interestService.submit(new SubmitCommand(NAME, EMAIL, ORG, MSG, null, null), "1.1.1.1");

        // The raw interestRequests doc carries ciphertext only — no plaintext PII.
        for (Document raw : mongoTemplate.getCollection("interestRequests").find()) {
            String json = raw.toJson();
            assertThat(json).doesNotContain(NAME).doesNotContain(EMAIL).doesNotContain(ORG).doesNotContain(MSG);
        }
        // The recruiter notification rows are value-free (the notify threw, but if a row exists it carries no PII).
        for (RecruiterNotification n : mongoTemplate.findAll(RecruiterNotification.class)) {
            assertThat(n.toString()).doesNotContain(NAME).doesNotContain(EMAIL).doesNotContain(ORG).doesNotContain(MSG);
        }
        // The dead-letter collection carries no PII (a forced failure reduces to a cause-class string).
        for (Document raw : mongoTemplate.getCollection("deadLetterRecords").find()) {
            String json = raw.toJson();
            assertThat(json).doesNotContain(NAME).doesNotContain(EMAIL).doesNotContain(ORG).doesNotContain(MSG);
        }
    }
}
