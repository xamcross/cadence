package com.cadence.scheduling;

import com.cadence.BaseIntegrationTest;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.InterviewSlotClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D3: the claim uniqueness is PARTIAL on {@code status == ACTIVE} — once a claim is RELEASED it leaves the
 * index and a fresh ACTIVE claim for the same {@code (ws, member, startAt)} can be inserted (a reschedule /
 * rollback-then-rebook never deadlocks on a stale claim). No mocks.
 */
class ReleasedClaimNonCollisionTest extends BaseIntegrationTest {

    private static final String WS = "wsClaim";
    private static final String MEMBER = "333333333333333333333333";
    private static final Instant START = Instant.parse("2026-06-20T13:00:00Z");

    @BeforeEach
    void clean() {
        mongoTemplate.remove(new Query(), InterviewSlotClaim.class);
    }

    @Test
    void secondActiveClaim_collidesWhileFirstActive_butSucceedsOnceFirstReleased() {
        InterviewSlotClaim first = mongoTemplate.insert(
            new InterviewSlotClaim(WS, MEMBER, START, "req1", Instant.now()));

        // While the first is ACTIVE, a second ACTIVE claim for the same key collides on the partial index.
        assertThatThrownBy(() -> mongoTemplate.insert(
                new InterviewSlotClaim(WS, MEMBER, START, "req2", Instant.now())))
            .isInstanceOf(DuplicateKeyException.class);

        // Release the first (leaves the partial index).
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(first.getId())),
            new Update().set("status", ClaimStatus.RELEASED), InterviewSlotClaim.class);

        // Now a fresh ACTIVE claim for the same key succeeds.
        InterviewSlotClaim second = mongoTemplate.insert(
            new InterviewSlotClaim(WS, MEMBER, START, "req2", Instant.now()));
        assertThat(second.getId()).isNotNull();

        long active = mongoTemplate.find(
            new Query(Criteria.where("memberId").is(MEMBER).and("status").is(ClaimStatus.ACTIVE)),
            InterviewSlotClaim.class).size();
        assertThat(active).isEqualTo(1);
    }
}
