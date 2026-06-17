package com.cadence.scheduling;

import com.cadence.domain.ClaimStatus;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.scheduler.NoShowDefenseScheduler;
import com.cadence.security.SecureTokens;
import com.cadence.service.NoShowCascadeService;
import com.cadence.service.SchedulingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F23 higher-level coverage: the full escalate→release recovery flow (SC-004/SC-005), confirm-after-escalation
 * (US2 AC#3), per-workspace config honoured by the cascade (SC-011), and DST-correct absolute-instant timing
 * (SC-013). All deterministic against the mutable test clock.
 */
class NoShowCascadeExtraTest extends SchedulingItBase {

    @Autowired NoShowDefenseScheduler scheduler;
    @Autowired NoShowCascadeService cascade;
    @Autowired SchedulingService scheduling;
    @Autowired SchedulingRequestRepository requests;

    private String memberId;
    private String templateId;

    private void seedBase() {
        configuredWorkspace();
        memberId = member("iv@x.test", Role.RECRUITER).getId();
        templateId = seedTemplate(memberId).getId();
        seedContactableCandidate("cand1", "Dana", "dana@x.test");
    }

    private SchedulingRequest seedBooked(String candidateId, Instant start) {
        OfferedSlot chosen = slot("0", start, start.plus(Duration.ofHours(1)), List.of(memberId), List.of());
        return seedBookedRequest(candidateId, templateId, "Room", chosen, memberId).request;
    }

    private long activeClaims(String requestId) {
        return mongoTemplate.find(Query.query(Criteria.where("schedulingRequestId").is(requestId)
            .and("status").is(ClaimStatus.ACTIVE)), InterviewSlotClaim.class).size();
    }

    @Test
    void e2e_escalateThenRelease_recoversTheSlot() {   // SC-004 + SC-005
        seedBase();
        SchedulingRequest b = seedBooked("cand1", Instant.now(clock).plus(Duration.ofHours(1)));
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(b.getId())),
            new Update().set("confirmationRequestedAt", Instant.now(clock).minus(Duration.ofHours(22))),
            SchedulingRequest.class);

        scheduler.sweep();
        // The escalated state is OBSERVABLE in the booking record (SC-005), not just a transient signal.
        assertThat(requests.findById(b.getId()).orElseThrow().getEscalatedAt()).isNotNull();
        assertThat(activeClaims(b.getId())).isEqualTo(1);

        // Recruiter releases the slot.
        scheduling.cancelByRecruiter(WS, "admin", "cand1", "127.0.0.1");

        SchedulingRequest after = requests.findById(b.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.CANCELLED);
        // The slot is released back to available — no ACTIVE claim remains (re-selectable, SC-004).
        assertThat(activeClaims(b.getId())).isZero();
    }

    @Test
    void confirmAfterEscalation_stillConfirms() {   // US2 AC#3
        seedBase();
        SchedulingRequest b = seedBooked("cand1", Instant.now(clock).plus(Duration.ofHours(1)));
        String raw = SecureTokens.newToken();
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(b.getId())),
            new Update().set("confirmationRequestedAt", Instant.now(clock).minus(Duration.ofHours(22)))
                .set("escalatedAt", Instant.now(clock)).set("confirmTokenHash", hasher.hashToken(raw)),
            SchedulingRequest.class);

        NoShowCascadeService.ConfirmResult r = cascade.confirmAttendance(raw, "127.0.0.1");

        assertThat(r.status()).isEqualTo("confirmed");
        assertThat(requests.findById(b.getId()).orElseThrow().getCandidateConfirmedAt()).isNotNull();
    }

    @Test
    void cascadeHonoursPerWorkspaceLeadTime() {   // SC-011
        seedBase();
        // Custom 4h lead — well under the 24h global default.
        mongoTemplate.updateFirst(Query.query(Criteria.where("workspaceId").is(WS)),
            new Update().set("confirmationLeadTime", Duration.ofHours(4)), WorkspaceConfig.class);
        seedContactableCandidate("cand2", "Eve", "eve@x.test");
        SchedulingRequest beyond = seedBooked("cand1", Instant.now(clock).plus(Duration.ofHours(5))); // 5h > 4h lead
        SchedulingRequest within = seedBooked("cand2", Instant.now(clock).plus(Duration.ofHours(3))); // 3h < 4h lead

        scheduler.sweep();

        // With the default 24h lead the 5h booking would have been requested; the 4h override suppresses it.
        assertThat(requests.findById(beyond.getId()).orElseThrow().getConfirmationRequestedAt()).isNull();
        assertThat(requests.findById(within.getId()).orElseThrow().getConfirmationRequestedAt()).isNotNull();
    }

    @Test
    void firesAtAbsoluteInstant_acrossDstBoundary() {   // SC-013
        // A New-York workspace across the 2026 spring-forward (2026-03-08). The lead boundary is the ABSOLUTE
        // instant start - 24h; a regression to local-time math would drift it by an hour.
        configuredWorkspace(WS, "America/New_York", LocalTime.of(0, 0), LocalTime.of(23, 59));
        memberId = member("iv@x.test", Role.RECRUITER).getId();
        templateId = seedTemplate(memberId).getId();
        seedContactableCandidate("cand1", "Dana", "dana@x.test");
        Instant start = Instant.parse("2026-03-09T14:00:00Z");
        SchedulingRequest b = seedBooked("cand1", start);
        Instant boundary = start.minus(Duration.ofHours(24)); // 2026-03-08T14:00:00Z

        clock.set(boundary.minusSeconds(1)); // one second before the boundary
        scheduler.sweep();
        assertThat(requests.findById(b.getId()).orElseThrow().getConfirmationRequestedAt()).isNull();

        clock.set(boundary.plusSeconds(1)); // one second after the absolute boundary
        scheduler.sweep();
        assertThat(requests.findById(b.getId()).orElseThrow().getConfirmationRequestedAt()).isNotNull();
    }
}
