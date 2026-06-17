package com.cadence.status;

import com.cadence.domain.ErasureReasonCode;
import com.cadence.domain.ErasureRequest;
import com.cadence.domain.ErasureState;
import com.cadence.domain.RequestStatus;
import com.cadence.service.CandidateStatusService;
import com.cadence.service.ErasureRequestService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F30 T036 (Testcontainers): candidate erasure-request idempotency. A submit records exactly 1 PENDING; a
 * repeat records no second PENDING (the unique partial index is the guard); the candidate stays ACTIVE until
 * an Admin confirms. Also asserts the ChangeUnit015 indexes exist and that the pre-existing-duplicate dedupe
 * leaves at most one PENDING per candidate (the unique index would otherwise abort the build).
 */
class ErasureRequestIdempotencyIT extends StatusItBase {

    @Autowired CandidateStatusService service;
    @Autowired ErasureRequestService erasureRequests;

    private String tokenFor(String candidateId) {
        seedCandidate(candidateId, "Ada", candidateId + "@x.test");
        String link = service.statusLinkFor(WS, candidateId);
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    @Test
    void submit_recordsOnePending_repeatRecordsNoSecond_candidateStaysActive() {
        configuredWorkspace();
        String token = tokenFor("c1");

        service.requestErasureByToken(token, "ip-1");
        assertThat(pendingCount("c1")).isEqualTo(1);

        // Repeat -> still exactly one PENDING (idempotent).
        service.requestErasureByToken(token, "ip-1");
        service.requestErasureByToken(token, "ip-1");
        assertThat(pendingCount("c1")).isEqualTo(1);

        // The candidate is NOT erased — it waits for the Admin confirm.
        var c = mongoTemplate.findById("c1", com.cadence.domain.Candidate.class);
        assertThat(c).isNotNull();
        assertThat(c.getErasureState()).isEqualTo(ErasureState.ACTIVE);
    }

    @Test
    void changeUnit015_indexesExist() {
        List<Document> candIdx = indexes("candidates");
        Document statusToken = byKey(candIdx, new Document("statusTokenHash", 1));
        assertThat(statusToken).as("statusTokenHash index").isNotNull();
        assertThat(statusToken.getBoolean("unique", false)).isTrue();
        assertThat(statusToken.get("partialFilterExpression"))
            .isEqualTo(new Document("statusTokenHash", new Document("$exists", true)));

        List<Document> erIdx = indexes("erasureRequests");
        Document pending = byKey(erIdx, new Document("workspaceId", 1).append("candidateId", 1));
        assertThat(pending).as("erasureRequests PENDING idempotency index").isNotNull();
        assertThat(pending.getBoolean("unique", false)).isTrue();
        assertThat(pending.get("partialFilterExpression")).isEqualTo(new Document("status", "PENDING"));
    }

    @Test
    void directService_isIdempotentOnDuplicateKey() {
        configuredWorkspace();
        seedCandidate("c9", "Eve", "eve@x.test");
        ErasureRequest first = erasureRequests.requestErasure(WS, "c9", ErasureReasonCode.CANDIDATE_REQUEST);
        ErasureRequest again = erasureRequests.requestErasure(WS, "c9", ErasureReasonCode.CANDIDATE_REQUEST);
        assertThat(again.getId()).isEqualTo(first.getId()); // same row returned, no second insert
        assertThat(pendingCount("c9")).isEqualTo(1);
    }

    private long pendingCount(String candidateId) {
        return mongoTemplate.count(Query.query(
            Criteria.where("candidateId").is(candidateId).and("status").is(RequestStatus.PENDING)),
            ErasureRequest.class);
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
