package com.cadence.status;

import com.cadence.api.CandidateStatusDtos.CandidateStatusView;
import com.cadence.api.CandidateStatusDtos.DisplayState;
import com.cadence.api.CandidateStatusDtos.PublishStatusRequest;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.service.CandidateStatusService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F30 T014 (Testcontainers): the recruiter free text is ciphertext at rest (raw-driver read), and a
 * published-then-viewed round-trip decrypts correctly through the registered converter.
 */
class CandidateStatusViewIT extends StatusItBase {

    @Autowired CandidateStatusService service;

    @Test
    void statusFreeText_isCiphertextAtRest_andDecryptsOnView() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        String stage = "Onsite interview round 3";
        String next = "We are collecting interviewer feedback this week.";
        service.publish(WS, "c1", "actor",
            new PublishStatusRequest(CandidateStatusOutcome.IN_PROGRESS, stage, next, LocalDate.now(clock).plusDays(3)));

        // Raw driver: the stored statusStage/statusNextStep are ciphertext, never the plaintext.
        Document raw = mongoTemplate.getCollection("candidates").find(new Document("_id", "c1")).first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("statusStage")).isNotNull().isNotEqualTo(stage);
        assertThat(raw.getString("statusNextStep")).isNotNull().isNotEqualTo(next);
        assertThat(raw.toJson()).doesNotContain(stage).doesNotContain(next);
        // statusToken is provisioned + ciphertext; statusTokenHash is a plain HMAC (present).
        assertThat(raw.getString("statusToken")).isNotNull();
        assertThat(raw.getString("statusTokenHash")).isNotNull();

        // Round-trip: viewing by the token decrypts the converter-managed fields back to plaintext.
        String link = service.statusLinkFor(WS, "c1");
        String token = link.substring(link.indexOf("token=") + "token=".length());
        CandidateStatusView v = service.view(token, "1.2.3.4");
        assertThat(v.displayState()).isEqualTo(DisplayState.PUBLISHED);
        assertThat(v.stage()).isEqualTo(stage);
        assertThat(v.nextStep()).isEqualTo(next);
    }
}
