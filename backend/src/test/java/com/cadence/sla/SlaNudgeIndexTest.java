package com.cadence.sla;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F31 SC-013 (durable de-dup guarantee) — ChangeUnit016 must have created the unique PARTIAL index on
 * {@code slaNudgeDrafts {workspaceId,candidateId}} over {@code status:"OPEN"}. The SC-003 dedup rests on this
 * index being present and unique with the OPEN partial filter; without this assertion a remove-not-drop slip
 * could silently degrade dedup to a no-op over a missing index while every other test stays green.
 */
class SlaNudgeIndexTest extends SlaItBase {

    @Test
    void openDraftPartialUniqueIndex_exists() {
        boolean found = false;
        for (Document idx : mongoTemplate.getCollection("slaNudgeDrafts").listIndexes()) {
            Document key = idx.get("key", Document.class);
            if (key != null && key.containsKey("workspaceId") && key.containsKey("candidateId")) {
                Object partial = idx.get("partialFilterExpression");
                if (Boolean.TRUE.equals(idx.getBoolean("unique")) && partial instanceof Document p
                    && "OPEN".equals(p.get("status"))) {
                    found = true;
                }
            }
        }
        assertThat(found)
            .as("unique partial {workspaceId,candidateId} over status:OPEN on slaNudgeDrafts (ChangeUnit016)")
            .isTrue();
    }
}
