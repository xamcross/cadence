package com.cadence.ats;

import com.cadence.api.AtsExceptions;
import com.cadence.integration.AtsProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F40 US1: connection lifecycle — bad credential is not stored (SC-010), disconnect destroys the key (FR-005). */
class AtsConnectionIT extends AtsItBase {

    private static final String WS = "ws-conn";

    @Test
    void badCredentialIsNotStoredAndDoesNotLeak() {
        stub.program("GET", "/v1/jobs", 401); // provider rejects the key
        assertThatThrownBy(() -> connectionService.connect(WS, AtsProvider.GREENHOUSE, "bad-key"))
            .isInstanceOf(AtsExceptions.VerificationFailedException.class);
        // No usable connection stored, and the bad key never persisted (SC-010).
        assertThat(connections.findByWorkspaceIdAndProvider(WS, AtsProvider.GREENHOUSE)).isEmpty();
    }

    @Test
    void disconnectDestroysTheKeyAndStopsBeingCredentialSet() {
        connect(WS);
        assertThat(connectionService.health(WS, AtsProvider.GREENHOUSE).credentialSet()).isTrue();
        connectionService.disconnect(WS, AtsProvider.GREENHOUSE);
        assertThat(connectionService.health(WS, AtsProvider.GREENHOUSE).credentialSet()).isFalse();
        // Raw read: apiKey is cleared at rest (not present / null).
        var raw = mongoTemplate.getCollection("atsConnections").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("apiKey")).isNull();
    }
}
