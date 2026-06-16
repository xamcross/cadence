package com.cadence.emailtemplate;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T011: ChangeUnit009 creates the unique {workspaceId,messageType,stageKey} index on emailTemplates. */
class EmailTemplateIndexTest extends BaseIntegrationTest {

    @Test
    void uniqueCompoundIndexExists() {
        boolean found = false;
        for (Document idx : mongoTemplate.getCollection("emailTemplates").listIndexes()) {
            Document key = idx.get("key", Document.class);
            if (key != null && key.containsKey("workspaceId") && key.containsKey("messageType")
                && key.containsKey("stageKey")) {
                assertThat(idx.getBoolean("unique", false)).as("the email-template index is unique").isTrue();
                found = true;
            }
        }
        assertThat(found).as("emailTemplates {workspaceId,messageType,stageKey} index present").isTrue();
    }
}
