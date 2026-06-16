package com.cadence.scheduling;

import com.cadence.domain.AuthEventType;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.scheduler.SchedulingReaper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-017 recovery sweep: a request stuck in BOOKING with {@code updatedAt} stamped past the reaper threshold
 * is released back to PENDING_SELECTION and its ACTIVE claims released; a PENDING_SELECTION link past
 * {@code expiresAt} becomes EXPIRED with a SCHEDULING_LINK_EXPIRED audit. Time is driven deterministically by
 * stamping the past instants, never wall-clock sleeps.
 */
class SchedulingReaperTest extends SchedulingItBase {

    private static final String MEMBER = "111111111111111111111111";

    @Autowired SchedulingReaper reaper;

    private OfferedSlot openSlot() {
        return slot("0", Instant.parse("2026-06-20T13:00:00Z"), Instant.parse("2026-06-20T14:00:00Z"),
            List.of(MEMBER), List.of());
    }

    @Test
    void stuckBooking_isReleasedToPending_andClaimsReleased() {
        Seeded s = seedPendingRequest("cand1", "tmpl1", "Room 1", List.of(openSlot()));
        // Stuck in BOOKING with updatedAt well past the 10-minute reaper threshold.
        Instant longAgo = Instant.now(clock).minusSeconds(3600);
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(s.request.getId())),
            new Update().set("status", SchedulingStatus.BOOKING).set("chosenSlotId", "0")
                .set("updatedAt", longAgo), SchedulingRequest.class);
        InterviewSlotClaim claim = mongoTemplate.save(new InterviewSlotClaim(
            WS, MEMBER, Instant.parse("2026-06-20T13:00:00Z"), s.request.getId(), longAgo));

        reaper.sweep();

        SchedulingRequest after = mongoTemplate.findById(s.request.getId(), SchedulingRequest.class);
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.PENDING_SELECTION);
        assertThat(after.getChosenSlotId()).isNull();
        InterviewSlotClaim afterClaim = mongoTemplate.findById(claim.getId(), InterviewSlotClaim.class);
        assertThat(afterClaim.getStatus()).isEqualTo(ClaimStatus.RELEASED);
    }

    @Test
    void expiredPendingLink_becomesExpired_andAudited() {
        Seeded s = seedPendingRequest("cand1", "tmpl1", "Room 1", List.of(openSlot()));
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(s.request.getId())),
            new Update().set("expiresAt", Instant.parse("2026-06-01T00:00:00Z")), SchedulingRequest.class);

        reaper.sweep();

        SchedulingRequest after = mongoTemplate.findById(s.request.getId(), SchedulingRequest.class);
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.EXPIRED);
        assertThat(mongoTemplate.count(
                new Query(Criteria.where("eventType").is(AuthEventType.SCHEDULING_LINK_EXPIRED.name())),
                "authAuditLog"))
            .isEqualTo(1);
    }
}
