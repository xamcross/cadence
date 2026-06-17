package com.cadence.sla;

import com.cadence.domain.Role;
import com.cadence.domain.SlaDraftStatus;
import com.cadence.domain.SlaNudgeDraft;
import com.cadence.scheduler.SlaNudgeScheduler;
import com.cadence.service.SlaNudgeService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F31 PII discipline (SC-007/FR-024/FR-025). A breaching candidate is seeded with a high-entropy SENTINELF31NAME
 * name; after scan -> preview -> approve, neither the sentinel name nor the (decrypted) status-token may appear in
 * the persisted {@code slaNudgeDrafts} doc, the {@code emailDispatches} row, or the candidate {@code auditLog}. The
 * draft is PII-free by construction; the dispatch row carries no recipient/body; the status link materialises only
 * transiently into the merge context (returned to the recruiter, never persisted). The ci.yml scan is the stdout
 * backstop for captured logs.
 */
class SlaNudgeLogPiiScanTest extends SlaItBase {

    private static final String NAME = "SENTINELF31NAME_zz9";

    @Autowired SlaNudgeService sla;
    @Autowired SlaNudgeScheduler scheduler;

    @Test
    void scanPreviewApprove_doNotLeakNameOrStatusTokenAtRest() {
        configuredWorkspace();
        seedCandidate("c1", NAME, "ada@x.test", 10); // breaching, sentinel name (encrypted at rest)
        var rec = member("rec@x.test", Role.RECRUITER);

        scheduler.sweep();
        SlaNudgeDraft draft = mongoTemplate.findOne(Query.query(Criteria.where("candidateId").is("c1")
            .and("status").is(SlaDraftStatus.OPEN)), SlaNudgeDraft.class);
        assertThat(draft).isNotNull();

        // Preview decrypts the name into the returned body (recruiter read) — not persisted/logged.
        var preview = sla.previewDraft(WS, "c1");
        assertThat(preview.body()).isNotNull();

        // Resolve the status token (via the SPI) so we can assert it never lands at rest.
        String link = sla.previewDraft(WS, "c1").body(); // body carries the rendered status_link
        // The raw token is whatever statusLinkFor minted; pull it from the candidate doc decrypt path instead.
        var candStatus = mongoTemplate.findById("c1", com.cadence.domain.Candidate.class);
        String rawToken = candStatus.getStatusToken(); // converter-decrypted on read

        sla.approve(WS, draft.getId(), rec.getId());

        // (1) the persisted draft doc carries ids/enums/instants only — no name, no token.
        for (Document d : mongoTemplate.getCollection("slaNudgeDrafts").find(new Document("candidateId", "c1"))) {
            assertThat(d.toJson()).doesNotContain(NAME);
            if (rawToken != null) assertThat(d.toJson()).doesNotContain(rawToken);
        }
        // (2) the dispatch row carries no recipient/body/token.
        for (Document d : mongoTemplate.getCollection("emailDispatches").find(new Document("candidateId", "c1"))) {
            assertThat(d.toJson()).doesNotContain(NAME);
            if (rawToken != null) assertThat(d.toJson()).doesNotContain(rawToken);
        }
        // (3) the candidate audit log entries are value-free.
        for (Document a : mongoTemplate.getCollection("auditLog").find(new Document("candidateId", "c1"))) {
            assertThat(a.toJson()).doesNotContain(NAME);
            if (rawToken != null) assertThat(a.toJson()).doesNotContain(rawToken);
        }
    }
}
