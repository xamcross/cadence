package com.cadence.scheduling;

import com.cadence.domain.Candidate;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.ErasureState;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.scheduler.NoShowDefenseScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F23 cascade timing + idempotency (SC-001/003/006/009/011). Drives {@link NoShowDefenseScheduler#sweep()}
 * against the mutable test clock — no wall-clock sleeps. Defaults: lead 24h, escalation 2h before start.
 */
class NoShowCascadeTest extends SchedulingItBase {

    @Autowired NoShowDefenseScheduler scheduler;
    @Autowired SchedulingRequestRepository requests;

    private InterviewTemplate template;
    private String memberId;

    private void seedBase() {
        configuredWorkspace();
        memberId = member("interviewer@x.test", com.cadence.domain.Role.RECRUITER).getId();
        template = seedTemplate(memberId);
        seedContactableCandidate("cand1", "Dana Doe", "dana@x.test");
    }

    /** A BOOKED booking whose interview starts {@code now + startOffset}. */
    private SchedulingRequest seedBooked(String candidateId, Duration startOffset) {
        Instant start = Instant.now(clock).plus(startOffset);
        OfferedSlot chosen = slot("0", start, start.plus(Duration.ofHours(1)), List.of(memberId), List.of());
        return seedBookedRequest(candidateId, template.getId(), "Room 1", chosen, memberId).request;
    }

    private long reminderCount(String candidateId) {
        return mongoTemplate.count(Query.query(Criteria.where("candidateId").is(candidateId)
            .and("messageType").is(EmailMessageType.REMINDER_24H)), "emailDispatches");
    }

    private long unconfirmedAlerts(String candidateId) {
        return mongoTemplate.find(Query.query(Criteria.where("candidateId").is(candidateId)
            .and("type").is(RecruiterNotificationType.INTERVIEW_UNCONFIRMED)), RecruiterNotification.class).size();
    }

    @Test
    void stage1_requestsConfirmationWithinLeadTime_once() {
        seedBase();
        SchedulingRequest b = seedBooked("cand1", Duration.ofHours(23)); // within 24h lead, beyond 2h deadline

        scheduler.sweep();

        SchedulingRequest after = requests.findById(b.getId()).orElseThrow();
        assertThat(after.getConfirmationRequestedAt()).isNotNull();
        assertThat(after.getConfirmTokenHash()).isNotNull();
        assertThat(after.isConfirmationNotRequestable()).isFalse();
        assertThat(reminderCount("cand1")).isEqualTo(1);
        assertThat(after.getEscalatedAt()).isNull(); // not yet at the escalation deadline

        // Idempotent: a second sweep sends no duplicate reminder.
        scheduler.sweep();
        assertThat(reminderCount("cand1")).isEqualTo(1);
    }

    @Test
    void stage1_notDueBeyondLeadTime() {
        seedBase();
        seedBooked("cand1", Duration.ofHours(48)); // beyond the 24h lead -> not yet due
        scheduler.sweep();
        assertThat(reminderCount("cand1")).isZero();
        assertThat(requests.findFirstByWorkspaceIdAndCandidateIdOrderByCreatedAtDesc(WS, "cand1")
            .orElseThrow().getConfirmationRequestedAt()).isNull();
    }

    @Test
    void notContactable_noEmailButStillEscalates() {
        seedBase();
        // Withdraw consent -> not contactable.
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is("cand1")),
            new Update().set("erasureState", ErasureState.ACTIVE).set("lawfulBasis", null), Candidate.class);
        SchedulingRequest b = seedBooked("cand1", Duration.ofHours(1)); // within both lead and deadline

        scheduler.sweep();

        SchedulingRequest after = requests.findById(b.getId()).orElseThrow();
        assertThat(after.getConfirmationRequestedAt()).isNotNull();
        assertThat(after.isConfirmationNotRequestable()).isTrue();
        assertThat(after.getConfirmTokenHash()).isNull();
        assertThat(reminderCount("cand1")).isZero();
        // Same coarse alert fires (no oracle) because confirmationRequestedAt is set.
        assertThat(after.getEscalatedAt()).isNotNull();
        assertThat(unconfirmedAlerts("cand1")).isEqualTo(1);
    }

    @Test
    void stage2_escalatesUnconfirmed_once_andNotForConfirmed() {
        seedBase();
        seedContactableCandidate("cand2", "Eve", "eve@x.test");
        // Distinct starts so the two bookings' per-(member,start) claims do not collide on the unique index.
        SchedulingRequest unconfirmed = seedBooked("cand1", Duration.ofHours(1));
        SchedulingRequest confirmed = seedBooked("cand2", Duration.ofMinutes(90));
        // Both already had a confirmation requested; cand2 confirmed.
        Instant past = Instant.now(clock).minus(Duration.ofHours(22));
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(unconfirmed.getId())),
            new Update().set("confirmationRequestedAt", past), SchedulingRequest.class);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(confirmed.getId())),
            new Update().set("confirmationRequestedAt", past).set("candidateConfirmedAt", Instant.now(clock)),
            SchedulingRequest.class);

        scheduler.sweep();

        assertThat(requests.findById(unconfirmed.getId()).orElseThrow().getEscalatedAt()).isNotNull();
        assertThat(unconfirmedAlerts("cand1")).isEqualTo(1);
        assertThat(requests.findById(confirmed.getId()).orElseThrow().getEscalatedAt()).isNull();
        assertThat(unconfirmedAlerts("cand2")).isZero();

        scheduler.sweep(); // idempotent — no second alert
        assertThat(unconfirmedAlerts("cand1")).isEqualTo(1);
    }

    @Test
    void stage3_stampsNoShowAtStart_andDoesNotEscalatePastInterview() {
        seedBase();
        SchedulingRequest b = seedBooked("cand1", Duration.ofMinutes(-1)); // start just passed
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(b.getId())),
            new Update().set("confirmationRequestedAt", Instant.now(clock).minus(Duration.ofHours(23))),
            SchedulingRequest.class);

        scheduler.sweep();

        SchedulingRequest after = requests.findById(b.getId()).orElseThrow();
        assertThat(after.getNoShowAt()).isNotNull();
        assertThat(after.getEscalatedAt()).isNull(); // a past interview is never escalated (stage 2 guard)
    }

    /**
     * 032 T7 placement 4: a FREE workspace does not INITIATE a new confirmation-request cascade (stage 1), but
     * an ALREADY in-flight one (this row's stage 1 already ran) still completes -- stage 2 escalation is NOT
     * gated (spec US2-AS3).
     */
    @Test
    void noEntitlement_stage1Skips_butInFlightCascadeStillEscalates() {
        seedBase();
        // Downgrade to Free: stage 1 must not touch a fresh booking, even though it is due.
        mongoTemplate.remove(Query.query(Criteria.where("workspaceId").is(WS)), WorkspaceEntitlement.class);
        SchedulingRequest fresh = seedBooked("cand1", Duration.ofHours(1)); // within both lead and deadline

        scheduler.sweep();

        assertThat(requests.findById(fresh.getId()).orElseThrow().getConfirmationRequestedAt()).isNull();
        assertThat(reminderCount("cand1")).isZero();

        // An already in-flight cascade (stage 1 ran before the downgrade) still escalates on the SAME sweep.
        // A distinct start (90m, not 1h) so the two bookings' per-(member,start) claims do not collide.
        seedContactableCandidate("cand2", "Eve", "eve@x.test");
        SchedulingRequest inFlight = seedBooked("cand2", Duration.ofMinutes(90));
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(inFlight.getId())),
            new Update().set("confirmationRequestedAt", Instant.now(clock).minus(Duration.ofHours(22))),
            SchedulingRequest.class);

        scheduler.sweep();

        assertThat(requests.findById(inFlight.getId()).orElseThrow().getEscalatedAt()).isNotNull();
    }
}
