package com.cadence.scheduling;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T015: ChangeUnit012 created the six F13 indexes — including the UNIQUE PARTIAL claim index over
 * {@code status == ACTIVE} (the cross-request double-booking guard, D3). Asserted via listIndexes against
 * the live (Mongock-migrated) collections. No mocks.
 */
class SchedulingIndexTest extends BaseIntegrationTest {

    @Test
    void schedulingRequests_hasTokenHistoryExpiryStuckIndexes() {
        List<Document> idx = indexes("schedulingRequests");

        Document token = byKey(idx, new Document("tokenHash", 1));
        assertThat(token).as("unique tokenHash index").isNotNull();
        assertThat(token.getBoolean("unique", false)).isTrue();

        assertThat(byKey(idx, new Document("workspaceId", 1).append("candidateId", 1).append("createdAt", -1)))
            .as("per-candidate history index").isNotNull();
        assertThat(byKey(idx, new Document("status", 1).append("expiresAt", 1)))
            .as("reaper expiry index").isNotNull();
        assertThat(byKey(idx, new Document("status", 1).append("updatedAt", 1)))
            .as("reaper stuck-BOOKING index").isNotNull();
    }

    @Test
    void schedulingRequests_hasRescheduleIndexes() {   // F20 ChangeUnit013
        List<Document> idx = indexes("schedulingRequests");

        Document manage = byKey(idx, new Document("manageTokenHash", 1));
        assertThat(manage).as("manageTokenHash index").isNotNull();
        assertThat(manage.getBoolean("unique", false)).as("manageTokenHash is unique").isTrue();
        // Partial (NOT sparse) on $exists — paired with @Field(write=NON_NULL) so two cleared rows never collide.
        assertThat(manage.get("partialFilterExpression"))
            .as("manageTokenHash is partial on {$exists:true}")
            .isEqualTo(new Document("manageTokenHash", new Document("$exists", true)));

        assertThat(byKey(idx, new Document("rootRequestId", 1).append("mode", 1).append("status", 1)))
            .as("lineage / cap-derivation index").isNotNull();
        assertThat(byKey(idx, new Document("mode", 1).append("status", 1).append("updatedAt", 1)))
            .as("forward-commit recovery index").isNotNull();

        Document teardown = byKey(idx, new Document("calendarTeardownPending", 1));
        assertThat(teardown).as("erasure teardown index").isNotNull();
        assertThat(teardown.get("partialFilterExpression"))
            .as("teardown index is partial on true")
            .isEqualTo(new Document("calendarTeardownPending", true));
    }

    @Test
    void interviewSlotClaims_hasUniquePartialActiveIndex_andReleaseIndex() {
        List<Document> idx = indexes("interviewSlotClaims");

        Document claim = byKey(idx, new Document("workspaceId", 1).append("memberId", 1).append("startAt", 1));
        assertThat(claim).as("claim key index").isNotNull();
        assertThat(claim.getBoolean("unique", false)).as("claim index is unique").isTrue();
        // The partial filter on status==ACTIVE is the load-bearing property — a RELEASED claim leaves the index.
        assertThat(claim.get("partialFilterExpression"))
            .as("claim index is partial on status==ACTIVE")
            .isEqualTo(new Document("status", "ACTIVE"));

        assertThat(byKey(idx, new Document("workspaceId", 1).append("schedulingRequestId", 1)))
            .as("release-set lookup index").isNotNull();
    }

    @Test
    void schedulingRequests_hasNoShowIndexes_andConfirmTokenPartialUnique() {   // F23 ChangeUnit014 (T015)
        List<Document> idx = indexes("schedulingRequests");

        assertThat(byKey(idx, new Document("status", 1).append("bookedStartAt", 1)))
            .as("no-show cascade sweep index").isNotNull();

        Document confirm = byKey(idx, new Document("confirmTokenHash", 1));
        assertThat(confirm).as("confirmTokenHash index").isNotNull();
        assertThat(confirm.getBoolean("unique", false)).as("confirmTokenHash is unique").isTrue();
        // Partial (NOT sparse) on $exists — paired with @Field(write=NON_NULL) so two cleared rows never collide.
        assertThat(confirm.get("partialFilterExpression"))
            .as("confirmTokenHash is partial on {$exists:true}")
            .isEqualTo(new Document("confirmTokenHash", new Document("$exists", true)));
    }

    @Test
    void confirmTokenHash_twoClearedRowsDoNotCollide() {   // F23 (T045) — the F01 present-as-null footgun
        com.cadence.domain.SchedulingRequest a = bookedWithoutConfirmToken("noshow-idx-a");
        com.cadence.domain.SchedulingRequest b = bookedWithoutConfirmToken("noshow-idx-b");
        try {
            // @Field(write=NON_NULL) omits the null confirmTokenHash from BSON; the partial index only covers
            // {$exists:true}, so two token-less BOOKED rows do NOT collide on the unique index.
            mongoTemplate.insert(a);
            mongoTemplate.insert(b);
            assertThat(mongoTemplate.findById("noshow-idx-a", com.cadence.domain.SchedulingRequest.class)).isNotNull();
            assertThat(mongoTemplate.findById("noshow-idx-b", com.cadence.domain.SchedulingRequest.class)).isNotNull();
        } finally {
            mongoTemplate.remove(a);
            mongoTemplate.remove(b);
        }
    }

    private static com.cadence.domain.SchedulingRequest bookedWithoutConfirmToken(String id) {
        com.cadence.domain.SchedulingRequest r = new com.cadence.domain.SchedulingRequest();
        r.setId(id);
        r.setWorkspaceId("ws-idx");
        r.setCandidateId("c-idx");
        r.setTemplateId("t-idx");
        r.setStatus(com.cadence.domain.SchedulingStatus.BOOKED);
        r.setTokenHash("th-" + id);   // distinct slot-pick token (the F13 {tokenHash} unique index is not partial)
        // confirmTokenHash deliberately left null (omitted from BSON via @Field(write=NON_NULL)).
        return r;
    }

    private List<Document> indexes(String collection) {
        List<Document> out = new ArrayList<>();
        mongoTemplate.getCollection(collection).listIndexes().forEach(out::add);
        return out;
    }

    private static Document byKey(List<Document> indexes, Document key) {
        return indexes.stream().filter(d -> key.equals(d.get("key"))).findFirst().orElse(null);
    }
}
