package com.cadence.interest;

import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.service.InterestRequestService.SubmitCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T032/SC-011/US3 Sc.3: a new submit creates exactly one value-free RecruiterNotification (type INTEREST_REQUEST,
 * null candidateId, no submitter PII); a same-email burst (coalesced) yields exactly one row; and submit enqueues
 * ZERO emailDispatches / outbound mail (the notification is never emailed to the submitter — structural
 * anti-amplification).
 */
class InterestNotificationIT extends InterestItBase {

    @Test
    void newSubmit_createsOneValueFreeNotification_noOutboundMail() {
        String sentinelName = "SENTINELF70NAME_zz9";
        interestService.submit(new SubmitCommand(sentinelName, "n@example.com", null, null, null, null), "1.1.1.1");

        List<RecruiterNotification> notes = mongoTemplate.findAll(RecruiterNotification.class);
        assertThat(notes).hasSize(1);
        RecruiterNotification n = notes.get(0);
        assertThat(n.getType()).isEqualTo(RecruiterNotificationType.INTEREST_REQUEST);
        assertThat(n.getWorkspaceId()).isEqualTo(WS);
        assertThat(n.getCandidateId()).isNull(); // value-free; no submitter PII anywhere
        assertThat(n.toString()).doesNotContain(sentinelName).doesNotContain("n@example.com");

        // Structural anti-amplification: NO email dispatch / outbound mail row was created by the submit path.
        assertThat(mongoTemplate.getCollection("emailDispatches").countDocuments()).isZero();
    }

    @Test
    void sameEmailBurst_coalesced_yieldsExactlyOneNotification() {
        SubmitCommand cmd = new SubmitCommand("Dana", "burst@example.com", null, null, null, null);
        // Rotate the source IP so the per-source limiter (test cap 3) does not trip before the burst lands; the
        // dedup is what we are exercising here, and the per-workspace ceiling (5) is above the burst size.
        for (int i = 0; i < 4; i++) {
            interestService.submit(cmd, "7.7.7." + i);
        }
        assertThat(mongoTemplate.findAll(RecruiterNotification.class)).hasSize(1);
    }
}
