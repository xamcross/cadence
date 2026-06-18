package com.cadence.ats;

import com.cadence.api.AtsExceptions;
import com.cadence.domain.AtsConnectionStatus;
import com.cadence.integration.AtsProvider;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F41 US1: connect Lever, coexist with Greenhouse, the key is ciphertext at rest (SC-006), a bad key is not
 * stored (SC-010), and disconnecting one provider leaves the other intact (US1 AS5 / FR-031 coexistence).
 */
class AtsLeverConnectIT extends AtsItBase {

    private static final String WS = "ws-lever-conn";

    @Test
    void connectsLeverAndStoresTheKeyAsCiphertext() {
        connectLever(WS);
        assertThat(connectionService.health(WS, AtsProvider.LEVER).status())
            .isEqualTo(AtsConnectionStatus.CONNECTED);
        assertThat(connectionService.health(WS, AtsProvider.LEVER).credentialSet()).isTrue();
        // SC-006: the stored apiKey is ciphertext, never the plaintext, and never returned in the DTO.
        Document conn = mongoTemplate.getCollection("atsConnections").find().first();
        assertThat(conn).isNotNull();
        assertThat(conn.getString("apiKey")).isNotBlank();
        assertThat(conn.getString("apiKey")).isNotEqualTo("lever-key-" + WS);
    }

    @Test
    void badLeverCredentialIsNotStored() {
        leverStub.program("GET", "/v1/opportunities", 401);
        assertThatThrownBy(() -> connectionService.connect(WS, AtsProvider.LEVER, "bad-key"))
            .isInstanceOf(AtsExceptions.VerificationFailedException.class);
        assertThat(connections.findByWorkspaceIdAndProvider(WS, AtsProvider.LEVER)).isEmpty();
    }

    @Test
    void greenhouseAndLeverCoexistAndDisconnectIsIndependent() {
        connect(WS);       // Greenhouse
        connectLever(WS);  // Lever
        assertThat(connections.findByWorkspaceId(WS)).hasSize(2);
        assertThat(connectionService.listHealth(WS))
            .anyMatch(h -> h.provider() == AtsProvider.GREENHOUSE && h.status() == AtsConnectionStatus.CONNECTED)
            .anyMatch(h -> h.provider() == AtsProvider.LEVER && h.status() == AtsConnectionStatus.CONNECTED);

        // Disconnect Lever -> Greenhouse remains CONNECTED with its key intact.
        connectionService.disconnect(WS, AtsProvider.LEVER);
        assertThat(connectionService.health(WS, AtsProvider.LEVER).status())
            .isEqualTo(AtsConnectionStatus.DISCONNECTED);
        assertThat(connectionService.health(WS, AtsProvider.GREENHOUSE).status())
            .isEqualTo(AtsConnectionStatus.CONNECTED);
        assertThat(connectionService.health(WS, AtsProvider.GREENHOUSE).credentialSet()).isTrue();
    }
}
