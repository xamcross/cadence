package com.cadence.emaildelivery;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T012: ChangeUnit010 creates the four emailDispatches indexes (data-model §1) — asserted via
 * {@code listIndexes}: unique {workspaceId,idempotencyKey}, {status,nextAttemptAt},
 * sparse {providerMessageRef}, {workspaceId,candidateId,createdAt:-1}.
 */
class EmailDispatchIndexTest extends BaseIntegrationTest {

    @Test
    void allFourDispatchIndexesExist() {
        boolean unique = false, due = false, providerRef = false, history = false;

        for (Document idx : mongoTemplate.getCollection("emailDispatches").listIndexes()) {
            Document key = idx.get("key", Document.class);
            if (key == null) {
                continue;
            }
            if (key.containsKey("workspaceId") && key.containsKey("idempotencyKey")) {
                assertThat(idx.getBoolean("unique", false))
                    .as("{workspaceId,idempotencyKey} is unique").isTrue();
                unique = true;
            } else if (key.containsKey("status") && key.containsKey("nextAttemptAt")) {
                due = true;
            } else if (key.containsKey("providerMessageRef") && key.size() == 1) {
                assertThat(idx.getBoolean("sparse", false))
                    .as("{providerMessageRef} is sparse").isTrue();
                providerRef = true;
            } else if (key.containsKey("workspaceId") && key.containsKey("candidateId")
                    && key.containsKey("createdAt")) {
                assertThat(key.getInteger("createdAt")).as("createdAt is descending").isEqualTo(-1);
                history = true;
            }
        }

        assertThat(unique).as("unique {workspaceId,idempotencyKey} present").isTrue();
        assertThat(due).as("{status,nextAttemptAt} present").isTrue();
        assertThat(providerRef).as("sparse {providerMessageRef} present").isTrue();
        assertThat(history).as("{workspaceId,candidateId,createdAt:-1} present").isTrue();
    }
}
