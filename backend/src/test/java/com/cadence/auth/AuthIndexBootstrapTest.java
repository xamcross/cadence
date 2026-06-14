package com.cadence.auth;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies ChangeUnit002 created the auth indexes (F00.1 pattern; T030). Cleans by remove, never
 * dropCollection (CLAUDE.md) — though this test only reads index metadata.
 */
class AuthIndexBootstrapTest extends BaseIntegrationTest {

    private List<String> indexNames(String collection) {
        List<String> names = new ArrayList<>();
        for (Document idx : mongoTemplate.getCollection(collection).listIndexes()) {
            names.add(idx.getString("name"));
        }
        return names;
    }

    @Test
    void membersHasUniqueEmailAndPartialSsoIndexes() {
        List<String> names = indexNames("members");
        assertThat(names).contains("workspaceId_1_emailHash_1", "ssoProvider_1_ssoSubject_1");
    }

    @Test
    void ttlAndUniqueTokenIndexesExist() {
        assertThat(indexNames("invitations")).contains("tokenHash_1", "expiresAt_1");
        assertThat(indexNames("passwordResets")).contains("tokenHash_1", "expiresAt_1");
        assertThat(indexNames("sessions")).contains("memberId_1", "absoluteExpiresAt_1");
        assertThat(indexNames("authAuditLog")).contains("memberId_1_occurredAt_-1");
    }
}
