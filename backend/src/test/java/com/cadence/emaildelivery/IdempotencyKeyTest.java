package com.cadence.emaildelivery;

import com.cadence.domain.EmailMessageType;
import com.cadence.service.IdempotencyKeys;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T028 (US2) — the dispatch idempotency key: {@code sha256(workspaceId|candidateId|messageType|
 * scheduledForEpochMillis)} length-prefixed. Pure unit (no Spring/Mongo). Asserts stability (same inputs
 * -> same key) and distinctness on each field — the durable exactly-once guarantee rests on this.
 */
class IdempotencyKeyTest {

    private static final long T0 = 1_700_000_000_000L;

    @Test
    void stable_sameInputs_sameKey() {
        String a = IdempotencyKeys.dispatchKey("ws1", "c1", EmailMessageType.CONFIRMATION, T0);
        String b = IdempotencyKeys.dispatchKey("ws1", "c1", EmailMessageType.CONFIRMATION, T0);
        assertThat(a).isEqualTo(b).isNotBlank();
    }

    @Test
    void distinct_onWorkspace() {
        assertThat(IdempotencyKeys.dispatchKey("ws1", "c1", EmailMessageType.CONFIRMATION, T0))
            .isNotEqualTo(IdempotencyKeys.dispatchKey("ws2", "c1", EmailMessageType.CONFIRMATION, T0));
    }

    @Test
    void distinct_onCandidate() {
        assertThat(IdempotencyKeys.dispatchKey("ws1", "c1", EmailMessageType.CONFIRMATION, T0))
            .isNotEqualTo(IdempotencyKeys.dispatchKey("ws1", "c2", EmailMessageType.CONFIRMATION, T0));
    }

    @Test
    void distinct_onMessageType() {
        assertThat(IdempotencyKeys.dispatchKey("ws1", "c1", EmailMessageType.CONFIRMATION, T0))
            .isNotEqualTo(IdempotencyKeys.dispatchKey("ws1", "c1", EmailMessageType.REJECTION, T0));
    }

    @Test
    void distinct_onScheduledFor() {
        assertThat(IdempotencyKeys.dispatchKey("ws1", "c1", EmailMessageType.CONFIRMATION, T0))
            .isNotEqualTo(IdempotencyKeys.dispatchKey("ws1", "c1", EmailMessageType.CONFIRMATION, T0 + 1));
    }

    @Test
    void lengthPrefixed_noConcatenationCollision() {
        // ("ws","ab") vs ("wsa","b") must not collide thanks to length-prefixing.
        assertThat(IdempotencyKeys.dispatchKey("ws", "ab", EmailMessageType.CONFIRMATION, T0))
            .isNotEqualTo(IdempotencyKeys.dispatchKey("wsa", "b", EmailMessageType.CONFIRMATION, T0));
    }
}
