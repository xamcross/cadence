package com.cadence.scheduling;

import com.cadence.domain.AtsWriteBack;
import com.cadence.domain.Candidate;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.ErasureState;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.integration.AtsProvider;
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

    /**
     * ATS-link a seeded candidate so an F40 write-back enqueue is NOT vacuously a no-op -- without a provider ref
     * {@code AtsWriteBackService.enqueue} returns early, which would make a "no write-back row" assertion circular.
     */
    private void linkToAts(String candidateId) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(candidateId)),
            new Update().set("atsProvider", AtsProvider.GREENHOUSE).set("atsExternalRef", "ext-" + candidateId),
            Candidate.class);
    }

    private long writeBacks(String candidateId) {
        return mongoTemplate.count(Query.query(Criteria.where("candidateId").is(candidateId)), AtsWriteBack.class);
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

    /**
     * 032 round 2: stage 3 ALWAYS stamps -- {@code findNoShowDue} has no lower time bound and nothing else ever
     * writes {@code noShowAt}, so a skipped row would never leave the candidate set and would eventually starve
     * the capped batch for every workspace. What the plan gate suppresses is the OUTBOUND side effect: a FREE
     * workspace gets no fresh F40 ATS write-back (the ungated drain would otherwise deliver it to a connection
     * the gate reports as "paused", US2-AS2). This is the fresh-row half (stage 1 never ran).
     */
    @Test
    void noEntitlement_stage3StampsFreshRow_butEnqueuesNoAtsWriteBack() {
        seedBase();
        mongoTemplate.remove(Query.query(Criteria.where("workspaceId").is(WS)), WorkspaceEntitlement.class);
        seedContactableCandidate("candFreeFresh", "Hana", "hana@x.test");
        linkToAts("candFreeFresh");
        SchedulingRequest fresh = seedBooked("candFreeFresh", Duration.ofMinutes(-1)); // start passed, stage 1 never ran

        scheduler.sweep();

        SchedulingRequest after = requests.findById(fresh.getId()).orElseThrow();
        assertThat(after.getConfirmationRequestedAt()).isNull(); // fixture check: the cascade truly never started
        assertThat(after.getNoShowAt()).isNotNull();             // the local stamp always happens (no starvation)
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.BOOKED);
        assertThat(writeBacks("candFreeFresh")).isZero();        // ...but no provider write-back on Free
    }

    /**
     * The in-flight half of the same rule: an already-started cascade completes its stamp on Free (US2-AS3), yet
     * it STILL gets no fresh provider write-back -- "no new provider writes for a non-entitled workspace" holds
     * in-flight or not. The Team control below keeps both "no write-back" assertions non-circular.
     */
    @Test
    void noEntitlement_stage3StampsInFlightRow_butEnqueuesNoAtsWriteBack() {
        seedBase();
        mongoTemplate.remove(Query.query(Criteria.where("workspaceId").is(WS)), WorkspaceEntitlement.class);
        seedContactableCandidate("candFreeInFlight", "Iris", "iris@x.test");
        linkToAts("candFreeInFlight");
        SchedulingRequest inFlight = seedBooked("candFreeInFlight", Duration.ofMinutes(-1));
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(inFlight.getId())),
            new Update().set("confirmationRequestedAt", Instant.now(clock).minus(Duration.ofHours(23))),
            SchedulingRequest.class);

        scheduler.sweep();

        assertThat(requests.findById(inFlight.getId()).orElseThrow().getNoShowAt()).isNotNull();
        assertThat(writeBacks("candFreeInFlight")).isZero();
    }

    /**
     * Team control for the two tests above: the ENTITLED workspace (seeded by {@link SchedulingItBase}) both
     * stamps and enqueues the F40 write-back on the very same fixture -- so "zero write-backs" on Free is a real
     * gate, not a fixture that could never enqueue one.
     */
    @Test
    void entitled_stage3StampsAndEnqueuesTheAtsWriteBack() {
        seedBase();
        seedContactableCandidate("candTeamNoShow", "Jo", "jo@x.test");
        linkToAts("candTeamNoShow");
        SchedulingRequest b = seedBooked("candTeamNoShow", Duration.ofMinutes(-1));

        scheduler.sweep();

        assertThat(requests.findById(b.getId()).orElseThrow().getNoShowAt()).isNotNull();
        assertThat(writeBacks("candTeamNoShow")).isEqualTo(1);
    }
}
