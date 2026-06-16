package com.cadence.scheduling;

import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingRequest;
import com.cadence.scheduler.NoShowDefenseScheduler;
import org.bson.Document;
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
 * F23 PII discipline (SC-010). The candidate name + recruiter location are seeded as high-entropy
 * SENTINELF23* tokens (the ci.yml scan re-checks them in captured stdout). The cascade/escalation must not
 * leak them into the persisted {@code schedulingRequests} doc (locationText is encrypted), the value-free
 * recruiter notification, or the audit log — all ids / {@code .name()} only.
 */
class NoShowLogPiiScanTest extends SchedulingItBase {

    private static final String NAME = "SENTINELF23NAME_zz9";
    private static final String LOC = "SENTINELF23BODY_zz9";

    @Autowired NoShowDefenseScheduler scheduler;

    @Test
    void cascade_doesNotLeakCandidateNameOrLocation() {
        configuredWorkspace();
        String memberId = member("iv@x.test", com.cadence.domain.Role.RECRUITER).getId();
        String templateId = seedTemplate(memberId).getId();
        seedContactableCandidate("cand1", NAME, "sentinel-f23@x.invalid");
        Instant start = Instant.now(clock).plus(Duration.ofHours(1)); // within both lead + deadline
        OfferedSlot chosen = slot("0", start, start.plus(Duration.ofHours(1)), List.of(memberId), List.of());
        SchedulingRequest b = seedBookedRequest("cand1", templateId, LOC, chosen, memberId).request;

        scheduler.sweep(); // stage 1 (request) + stage 2 (escalate)

        // The persisted scheduling-request doc carries ciphertext for locationText, never the plaintext sentinel.
        Document doc = mongoTemplate.getCollection("schedulingRequests")
            .find(new Document("_id", new org.bson.types.ObjectId(b.getId()))).first();
        assertThat(doc).isNotNull();
        assertThat(doc.toJson()).doesNotContain(NAME).doesNotContain(LOC);

        // The value-free recruiter notification carries ids + type only.
        Document notif = mongoTemplate.getCollection("recruiterNotifications")
            .find(new Document("candidateId", "cand1")).first();
        assertThat(notif).isNotNull();
        assertThat(notif.toJson()).doesNotContain(NAME).doesNotContain(LOC);

        // The audit log entries are value-free outcome literals.
        for (Document a : mongoTemplate.getCollection("authAuditLog").find(new Document("workspaceId", WS))) {
            assertThat(a.toJson()).doesNotContain(NAME).doesNotContain(LOC);
        }
    }
}
