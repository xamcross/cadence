package com.cadence.feedback;

import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-014 (persisted-artefact leg; the CI log-grep is the captured-stdout backstop): scorecard free text + the
 * candidate name (PII sentinels) never appear in the persisted {@code feedbackRequests} docs (only the encrypted
 * payload) nor in the candidate audit records, across generate -> submit.
 */
class FeedbackLogPiiScanTest extends FeedbackItBase {

    private static final String TEXT = "SENTINELF32TEXTzz9";
    private static final String NAME = "SENTINELF32NAMEzz9";

    @Test
    void noScorecardContentOrCandidateName_inPersistedArtefacts() {
        configuredWorkspace();
        seedCandidate("cand1", NAME, "dana@example.com");
        Member a = member("a@example.com", Role.INTERVIEWER);
        seedBookedInterview("req1", "cand1", 4);
        seedClaim("req1", a.getId(), Instant.now(clock).minus(Duration.ofHours(4)));
        scheduler.sweep();
        FeedbackRequest r = feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, "req1").get(0);
        feedbackService.submit(r.getToken(),
            new com.cadence.api.FeedbackDtos.ScorecardSubmission("YES", java.util.List.of(), TEXT), "1.1.1.1");

        // The raw feedbackRequests docs carry no plaintext scorecard text and no candidate name (payload encrypted).
        for (Document raw : mongoTemplate.getCollection("feedbackRequests").find()) {
            assertThat(raw.toJson()).doesNotContain(TEXT).doesNotContain(NAME);
        }
        // The candidate audit records are value-free (event codes only).
        for (CandidateAuditEvent e : mongoTemplate.findAll(CandidateAuditEvent.class)) {
            assertThat(e.toString()).doesNotContain(TEXT).doesNotContain(NAME);
        }
        // SC-018: the submission produced exactly one value-free SCORECARD_SUBMITTED audit record.
        long submitted = mongoTemplate.count(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("candidateId").is("cand1")
                    .and("eventType").is(com.cadence.domain.CandidateEventType.SCORECARD_SUBMITTED)),
            CandidateAuditEvent.class);
        assertThat(submitted).isEqualTo(1);
    }
}
