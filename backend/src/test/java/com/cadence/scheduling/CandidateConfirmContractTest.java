package com.cadence.scheduling;

import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.security.SecureTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F23 candidate confirm endpoint contract (SC-008/FR-006/FR-007/FR-008). Public-by-token; affirmative POST;
 * status-before-time precedence (no oracle); idempotent replay; IDOR-safe; rate-limited.
 */
class CandidateConfirmContractTest extends SchedulingItBase {

    @Autowired SchedulingRequestRepository requests;

    private String memberId;
    private String templateId;

    private void seedBase() {
        configuredWorkspace();
        memberId = member("iv@x.test", com.cadence.domain.Role.RECRUITER).getId();
        templateId = seedTemplate(memberId).getId();
        seedContactableCandidate("cand1", "Dana", "dana@x.test");
    }

    /** A BOOKED booking with a known raw confirm token, interview starting {@code now + startOffset}. */
    private String seedConfirmable(String candidateId, Duration startOffset, SchedulingStatus status) {
        Instant start = Instant.now(clock).plus(startOffset);
        OfferedSlot chosen = slot("0", start, start.plus(Duration.ofHours(1)), List.of(memberId), List.of());
        SchedulingRequest b = seedBookedRequest(candidateId, templateId, "Room", chosen, memberId).request;
        String raw = SecureTokens.newToken();
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(b.getId())),
            new Update().set("confirmTokenHash", hasher.hashToken(raw))
                .set("confirmationRequestedAt", Instant.now(clock)).set("status", status),
            SchedulingRequest.class);
        return raw;
    }

    @Test
    void confirm_recordsOnce_andReplayIsNoOp() throws Exception {
        seedBase();
        String token = seedConfirmable("cand1", Duration.ofHours(20), SchedulingStatus.BOOKED);

        mvc.perform(post("/api/candidate/booking/{t}/confirm", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("confirmed"))
            .andExpect(jsonPath("$.bookedStart").exists());
        Instant first = requests.findByConfirmTokenHash(hasher.hashToken(token))
            .orElseThrow().getCandidateConfirmedAt();
        assertThat(first).isNotNull();

        // Replay: idempotent, still 200 confirmed, same timestamp (no second confirm).
        mvc.perform(post("/api/candidate/booking/{t}/confirm", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("confirmed"));
        assertThat(requests.findByConfirmTokenHash(hasher.hashToken(token)).orElseThrow()
            .getCandidateConfirmedAt()).isEqualTo(first);
    }

    @Test
    void getDoesNotConfirm() throws Exception {
        seedBase();
        String token = seedConfirmable("cand1", Duration.ofHours(20), SchedulingStatus.BOOKED);
        mvc.perform(get("/api/candidate/booking/{t}/confirm", token))
            .andExpect(status().isMethodNotAllowed());
        assertThat(requests.findByConfirmTokenHash(hasher.hashToken(token)).orElseThrow()
            .getCandidateConfirmedAt()).isNull();
    }

    @Test
    void pastInterview_returns410() throws Exception {
        seedBase();
        String token = seedConfirmable("cand1", Duration.ofMinutes(-5), SchedulingStatus.BOOKED);
        mvc.perform(post("/api/candidate/booking/{t}/confirm", token))
            .andExpect(status().isGone()).andExpect(jsonPath("$.error").value("expired"));
    }

    @Test
    void cancelledAndUnknown_areIndistinguishable400() throws Exception {
        seedBase();
        String cancelled = seedConfirmable("cand1", Duration.ofHours(20), SchedulingStatus.CANCELLED);
        mvc.perform(post("/api/candidate/booking/{t}/confirm", cancelled))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid"));
        mvc.perform(post("/api/candidate/booking/{t}/confirm", SecureTokens.newToken()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid"));
    }

    @Test
    void idor_confirmTokenActsOnlyOnItsOwnBooking() throws Exception {
        seedBase();
        seedContactableCandidate("cand2", "Eve", "eve@x.test");
        String tokenA = seedConfirmable("cand1", Duration.ofHours(20), SchedulingStatus.BOOKED);
        String tokenB = seedConfirmable("cand2", Duration.ofHours(21), SchedulingStatus.BOOKED); // distinct start

        mvc.perform(post("/api/candidate/booking/{t}/confirm", tokenA)).andExpect(status().isOk());

        // Confirming A must not confirm B.
        assertThat(requests.findByConfirmTokenHash(hasher.hashToken(tokenB)).orElseThrow()
            .getCandidateConfirmedAt()).isNull();
    }

    @Test
    void rateLimited_whenLimitExceeded() throws Exception {
        seedBase();
        // application-test.yml caps the candidate endpoints at 5 requests/minute/IP.
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/candidate/booking/{t}/confirm", SecureTokens.newToken()))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/candidate/booking/{t}/confirm", SecureTokens.newToken()))
            .andExpect(status().isTooManyRequests());
    }
}
