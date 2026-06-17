package com.cadence.scheduling;

import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.security.SecureTokens;
import com.cadence.service.CandidateErasureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F23 erasure interaction (FR-024/D9/SC-009): erasure cancels the BOOKED booking, $unsets the confirm token
 * (no usable link survives), and the cascade halts via the status:BOOKED guard.
 */
class ErasureDuringCascadeTest extends SchedulingItBase {

    @Autowired SchedulingRequestRepository requests;
    @Autowired CandidateErasureService erasure;

    @Test
    void erasureCancelsBooking_unsetsConfirmToken_andLinkIsDead() throws Exception {
        configuredWorkspace();
        String memberId = member("iv@x.test", com.cadence.domain.Role.RECRUITER).getId();
        String templateId = seedTemplate(memberId).getId();
        seedContactableCandidate("cand1", "Dana", "dana@x.test");
        Instant start = Instant.now(clock).plus(Duration.ofHours(20));
        OfferedSlot chosen = slot("0", start, start.plus(Duration.ofHours(1)), List.of(memberId), List.of());
        SchedulingRequest b = seedBookedRequest("cand1", templateId, "Room", chosen, memberId).request;
        String rawConfirm = SecureTokens.newToken();
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(b.getId())),
            new Update().set("confirmTokenHash", hasher.hashToken(rawConfirm))
                .set("confirmationRequestedAt", Instant.now(clock)), SchedulingRequest.class);

        erasure.wipe(WS, "cand1", CandidateAuditOutcome.OPERATOR, "admin");

        SchedulingRequest after = requests.findById(b.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.CANCELLED);
        assertThat(after.getConfirmTokenHash()).isNull(); // $unset — no usable confirm link survives
        // The cascade is halted (status != BOOKED) and the confirm link is dead -> indistinguishable 400.
        mvc.perform(post("/api/candidate/booking/{t}/confirm", rawConfirm))
            .andExpect(status().isBadRequest());
    }
}
