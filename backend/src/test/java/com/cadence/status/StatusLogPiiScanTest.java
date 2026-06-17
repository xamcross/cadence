package com.cadence.status;

import com.cadence.api.CandidateStatusDtos.PublishStatusRequest;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.service.CandidateErasureService;
import com.cadence.service.CandidateStatusService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F30 PII discipline (SC-006, T023/T033/T042). The recruiter free text is seeded as high-entropy SENTINELF30*
 * tokens (the ci.yml scan re-checks them in captured stdout). After a publish (which encrypts at rest), a view,
 * an erasure submit, and a wipe, none of the sentinels — and no raw status-token — may appear in the persisted
 * {@code candidates} doc, the audit log, or the erasure request. The status free text + token are ciphertext /
 * absent at rest; the decrypted statusLink is returned to the recruiter only, never persisted/audited.
 */
class StatusLogPiiScanTest extends StatusItBase {

    private static final String STAGE = "SENTINELF30STAGE_zz9";
    private static final String NEXT = "SENTINELF30NEXT_zz9";

    @Autowired CandidateStatusService service;
    @Autowired CandidateErasureService erasure;

    @Test
    void publishViewAndErasure_doNotLeakStatusTextOrTokenAtRest() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");

        // Publish with sentinel free text -> encrypted at rest.
        service.publish(WS, "c1", "actor", new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, STAGE, NEXT, LocalDate.now(clock).plusDays(2)));
        String link = service.statusLinkFor(WS, "c1");
        String rawToken = link.substring(link.indexOf("token=") + "token=".length());

        // View leg (no per-view audit).
        service.view(rawToken, "ip");

        // Erasure submit leg (records a PENDING request — id+enum only).
        service.requestErasureByToken(rawToken, "ip");

        // The persisted candidate doc carries ciphertext for the status free text + token, never the plaintext.
        Document cand = mongoTemplate.getCollection("candidates").find(new Document("_id", "c1")).first();
        assertThat(cand).isNotNull();
        assertThat(cand.toJson()).doesNotContain(STAGE).doesNotContain(NEXT).doesNotContain(rawToken);

        // The erasure request carries ids + enum reason only (no free text, no token).
        for (Document er : mongoTemplate.getCollection("erasureRequests").find(new Document("candidateId", "c1"))) {
            assertThat(er.toJson()).doesNotContain(STAGE).doesNotContain(NEXT).doesNotContain(rawToken);
        }

        // The candidate audit log entries are value-free outcome literals.
        for (Document a : mongoTemplate.getCollection("auditLog").find(new Document("candidateId", "c1"))) {
            assertThat(a.toJson()).doesNotContain(STAGE).doesNotContain(NEXT).doesNotContain(rawToken);
        }

        // Wipe leg — the erased doc carries no residual status text / token.
        erasure.wipe(WS, "c1", CandidateAuditOutcome.OPERATOR, "admin");
        Document erased = mongoTemplate.getCollection("candidates").find(new Document("_id", "c1")).first();
        assertThat(erased).isNotNull();
        assertThat(erased.toJson()).doesNotContain(STAGE).doesNotContain(NEXT).doesNotContain(rawToken);
        // statusToken is converter-managed -> cleared via $set null (key present, value null — no residual
        // ciphertext); statusTokenHash is a plain field -> $unset (key absent, out of the partial index).
        assertThat(erased.get("statusToken")).isNull();
        assertThat(erased.containsKey("statusTokenHash")).isFalse();
    }
}
