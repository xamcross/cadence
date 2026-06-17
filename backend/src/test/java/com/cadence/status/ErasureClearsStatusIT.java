package com.cadence.status;

import com.cadence.api.CandidateStatusDtos.PublishStatusRequest;
import com.cadence.api.CandidateStatusExceptions;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.service.CandidateErasureService;
import com.cadence.service.CandidateStatusService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F30 T035 (the BLOCKER): erasing a candidate with a provisioned token + published status must SUCCEED
 * (no ClassCastException from $unset on the converter-managed statusToken), null all status fields, clear
 * the token, make the old status token 404, and leave the candidate view indistinguishable from unknown.
 */
class ErasureClearsStatusIT extends StatusItBase {

    @Autowired CandidateStatusService service;
    @Autowired CandidateErasureService erasure;

    @Test
    void erasure_clearsStatusAndToken_atomically_noClassCastException() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        service.publish(WS, "c1", "actor", new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, "Onsite", "Collecting feedback", LocalDate.now(clock).plusDays(2)));
        String link = service.statusLinkFor(WS, "c1");
        String oldToken = link.substring(link.indexOf("token=") + "token=".length());
        // The view resolves before erasure.
        assertThat(service.view(oldToken, "ip")).isNotNull();

        // The wipe must NOT throw (the $set null on the converter-managed statusToken is the fix).
        boolean wiped = erasure.wipe(WS, "c1", CandidateAuditOutcome.OPERATOR, "admin");
        assertThat(wiped).isTrue();

        // Status fields nulled; statusToken cleared (raw driver), statusTokenHash unset (out of the partial index).
        Candidate c = mongoTemplate.findById("c1", Candidate.class);
        assertThat(c).isNotNull();
        assertThat(c.getErasureState()).isEqualTo(ErasureState.ERASED);
        assertThat(c.getStatusStage()).isNull();
        assertThat(c.getStatusNextStep()).isNull();
        assertThat(c.getStatusExpectedDate()).isNull();
        assertThat(c.getStatusOutcome()).isNull();
        assertThat(c.getStatusPublishedAt()).isNull();
        assertThat(c.getStatusToken()).isNull();
        assertThat(c.getStatusTokenHash()).isNull();

        Document raw = mongoTemplate.getCollection("candidates").find(new Document("_id", "c1")).first();
        assertThat(raw).isNotNull();
        assertThat(raw.containsKey("statusTokenHash")).as("statusTokenHash $unset (omitted from BSON)").isFalse();

        // The old status token now 404s (indistinguishable not-found).
        assertThatThrownBy(() -> service.view(oldToken, "ip"))
            .isInstanceOf(CandidateStatusExceptions.StatusNotFoundException.class);
    }
}
