package com.cadence.rbac;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies ChangeUnit003 created the F02 RBAC indexes with the exact generated names (T014, F00.1
 * pattern) so a mis-ordered key spec is caught. Reads index metadata only.
 */
class RbacIndexBootstrapTest extends BaseIntegrationTest {

    private List<String> indexNames(String collection) {
        List<String> names = new ArrayList<>();
        for (Document idx : mongoTemplate.getCollection(collection).listIndexes()) {
            names.add(idx.getString("name"));
        }
        return names;
    }

    @Test
    void lastAdminGuardIndexExistsOnMembers() {
        assertThat(indexNames("members")).contains("workspaceId_1_role_1_status_1");
    }

    @Test
    void assignmentScopingAndUniqueIndexesExist() {
        assertThat(indexNames("assignments")).contains(
            "workspaceId_1_memberId_1_resourceType_1",
            "workspaceId_1_resourceType_1_resourceId_1_memberId_1");
    }
}
