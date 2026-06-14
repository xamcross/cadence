package com.cadence.workspace;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T017: Mongock ChangeUnit004 created the unique singleton indexes. Asserts the exact generated
 * names so a mis-specified key would be caught (mirrors the F00.1/F02 bootstrap-test pattern).
 */
class WorkspaceConfigIndexBootstrapTest extends BaseIntegrationTest {

    private List<String> indexNames(String collection) {
        List<String> names = new ArrayList<>();
        for (Document d : mongoTemplate.getCollection(collection).listIndexes()) {
            names.add(d.getString("name"));
        }
        return names;
    }

    @Test
    void workspaceConfig_hasUniqueWorkspaceIdIndex() {
        assertThat(indexNames("workspaceConfig")).contains("workspaceId_1");
    }

    @Test
    void workspaceLogo_hasUniqueWorkspaceIdIndex() {
        assertThat(indexNames("workspaceLogo")).contains("workspaceId_1");
    }
}
