package com.cadence.interest;

import com.cadence.domain.InterestRequest;
import com.cadence.service.InterestRequestService.SubmitCommand;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T010: a submit persists a row with PII encrypted at rest (raw-driver ciphertext assert), emailHash/openEmailHash
 * stored as-is, and the workspaceId resolved from config (never the request — the SubmitCommand has no workspace).
 */
class InterestSubmitIT extends InterestItBase {

    @Test
    void submit_persistsEncryptedRow_hashesStoredAsIs_workspaceFromConfig() {
        String name = "Dana Lee F70";
        String email = "dana.f70@example.com";
        String org = "Acme Talent F70";
        String message = "We hire about 20 eng per quarter F70";
        interestService.submit(new SubmitCommand(name, email, org, message, null, null), "1.1.1.1");

        // The domain read decrypts via the converter.
        InterestRequest decrypted = interestRepo.findAll().get(0);
        assertThat(decrypted.getWorkspaceId()).isEqualTo(WS); // resolved from cadence.interest.default-workspace-id
        assertThat(decrypted.getName()).isEqualTo(name);
        assertThat(decrypted.getEmail()).isEqualTo(email);
        assertThat(decrypted.getOrganization()).isEqualTo(org);
        assertThat(decrypted.getMessage()).isEqualTo(message);
        assertThat(decrypted.getEmailHash()).isEqualTo(crypto.emailHash(email));
        assertThat(decrypted.getOpenEmailHash()).isEqualTo(crypto.emailHash(email));

        // The RAW BSON carries ciphertext (no plaintext PII) but the hashes verbatim.
        Document raw = mongoTemplate.getCollection("interestRequests").find().first();
        assertThat(raw).isNotNull();
        String json = raw.toJson();
        assertThat(json).doesNotContain(name).doesNotContain(email).doesNotContain(org).doesNotContain(message);
        assertThat(raw.getString("emailHash")).isEqualTo(crypto.emailHash(email));
        assertThat(raw.getString("openEmailHash")).isEqualTo(crypto.emailHash(email));
        assertThat(raw.getString("workspaceId")).isEqualTo(WS);
    }
}
