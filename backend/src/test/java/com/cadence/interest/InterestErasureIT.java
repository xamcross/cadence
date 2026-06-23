package com.cadence.interest;

import com.cadence.domain.InterestRequest;
import com.cadence.service.CandidateErasureService;
import com.cadence.service.InterestRequestService.SubmitCommand;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T025/FR-022: admin erase $set "[ERASED]" on the 4 PII fields + $unset emailHash/openEmailHash; the row is no
 * longer discoverable by email; idempotent.
 */
class InterestErasureIT extends InterestItBase {

    @Test
    void erase_setsMarker_unsetsHashes_notDiscoverableByEmail_idempotent() {
        String email = "erase.me@example.com";
        interestService.submit(new SubmitCommand("Dana", email, "Acme", "msg", null, null), "1.1.1.1");
        InterestRequest req = interestRepo.findAll().get(0);

        interestService.erase(WS, req.getId());

        // The encrypted fields decrypt back to the marker (NEVER $unset an encrypted field — F03 trap).
        InterestRequest after = interestRepo.findByWorkspaceIdAndId(WS, req.getId()).orElseThrow();
        assertThat(after.getName()).isEqualTo(CandidateErasureService.ERASED_MARKER);
        assertThat(after.getEmail()).isEqualTo(CandidateErasureService.ERASED_MARKER);
        assertThat(after.getOrganization()).isEqualTo(CandidateErasureService.ERASED_MARKER);
        assertThat(after.getMessage()).isEqualTo(CandidateErasureService.ERASED_MARKER);
        assertThat(after.getEmailHash()).isNull();
        assertThat(after.getOpenEmailHash()).isNull();

        // The raw doc has neither hash key (dropped from the indexes -> not discoverable by email).
        Document raw = mongoTemplate.getCollection("interestRequests").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.containsKey("emailHash")).isFalse();
        assertThat(raw.containsKey("openEmailHash")).isFalse();
        assertThat(interestRepo.findByWorkspaceIdAndEmailHash(WS, crypto.emailHash(email))).isEmpty();

        // Idempotent — a second erase is a benign no-op (same shape, no oracle).
        interestService.erase(WS, req.getId());
        InterestRequest after2 = interestRepo.findByWorkspaceIdAndId(WS, req.getId()).orElseThrow();
        assertThat(after2.getName()).isEqualTo(CandidateErasureService.ERASED_MARKER);
    }
}
