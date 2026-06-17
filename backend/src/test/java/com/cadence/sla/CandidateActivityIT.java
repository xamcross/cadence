package com.cadence.sla;

import com.cadence.api.CandidateStatusDtos.PublishStatusRequest;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.ErasureState;
import com.cadence.domain.SlaState;
import com.cadence.emaildelivery.RecordingMailTransport;
import com.cadence.integration.MailTransport;
import com.cadence.service.CandidateActivityService;
import com.cadence.service.CandidateStatusService;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.SlaNudgeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F31 SC-014 — each qualifying activity advances the canonical last-meaningful-activity instant and clears an SLA
 * breach. Sites 1 (outbound candidate email SENT) and 2 (status publish) are driven end-to-end; the shared
 * {@link CandidateActivityService} helper that sites 1/3/4/5 all call is tested directly (so booking/reschedule —
 * exercised by the scheduling-package confirm ITs — are covered by the same proven mechanism). Site 5 (approve) is
 * asserted independently in {@link SlaNudgeIT}. A {@link RecordingMailTransport} makes the dispatch reach SENT.
 */
@Import(CandidateActivityIT.TransportConfig.class)
class CandidateActivityIT extends SlaItBase {

    @TestConfiguration
    static class TransportConfig {
        @Bean @Primary
        MailTransport recordingMailTransport() {
            return new RecordingMailTransport();
        }
    }

    @Autowired CandidateActivityService activity;
    @Autowired CandidateStatusService status;
    @Autowired EmailDispatchService dispatch;
    @Autowired SlaNudgeService sla;

    private SlaState slaState(String candidateId) {
        return sla.candidateSla(WS, candidateId).slaState();
    }

    @Test
    void helper_advancesActive_andIsNoOpOnErased() {
        configuredWorkspace();
        Candidate active = seedCandidate("c1", "Ada", "ada@x.test", 10);
        Candidate erased = seedCandidate("c2", "Ben", "ben@x.test", 10);
        erased.setErasureState(ErasureState.ERASED);
        mongoTemplate.save(erased);
        Instant now = Instant.now(clock);

        activity.advanceLastContact(WS, "c1", now);
        activity.advanceLastContact(WS, "c2", now);

        assertThat(mongoTemplate.findById("c1", Candidate.class).getLastContactAt()).isEqualTo(now);
        // ACTIVE-guarded: an erased candidate's instant is never moved.
        assertThat(mongoTemplate.findById("c2", Candidate.class).getLastContactAt())
            .isEqualTo(erased.getLastContactAt());
        assertThat(slaState("c1")).isEqualTo(SlaState.GREEN); // breach cleared
    }

    @Test
    void site2_statusPublish_advancesAndClearsBreach() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test", 10);
        assertThat(slaState("c1")).isEqualTo(SlaState.RED);

        status.publish(WS, "c1", "actor", new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, "Onsite", "Collecting feedback", LocalDate.now(clock).plusDays(3)));

        // The fragile inline $set on the publish path must advance lastContactAt (site 2).
        assertThat(slaState("c1")).isEqualTo(SlaState.GREEN);
    }

    @Test
    void site1_candidateEmailSent_advancesAndClearsBreach() {
        configuredWorkspace();
        Candidate c = seedCandidate("c1", "Ada", "ada@x.test", 10);
        assertThat(slaState("c1")).isEqualTo(SlaState.RED);

        // Enqueue + inline-dispatch a candidate email; the recording transport accepts -> SENT -> site 1 advance.
        dispatch.enqueue(WS, "c1", EmailMessageType.HOLD_UPDATE, "BASE", Instant.now(clock),
            Map.of("status_link", "https://app.example/status?token=x"), "c1");

        Candidate after = mongoTemplate.findById("c1", Candidate.class);
        assertThat(after.getLastContactAt()).isAfter(c.getLastContactAt());
        assertThat(slaState("c1")).isEqualTo(SlaState.GREEN);
    }
}
