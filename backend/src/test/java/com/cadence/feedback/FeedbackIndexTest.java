package com.cadence.feedback;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts ChangeUnit017 materialized the F32 indexes on startup (the F23 SchedulingIndexTest / F31
 * SlaNudgeIndexTest precedent): the unique {interviewEventId, interviewerMemberId}, the partial-unique
 * {tokenHash}, the {status, nextReminderDueAt}, and the pre-declared {interviewEventId, submittedAt}.
 */
class FeedbackIndexTest extends BaseIntegrationTest {

    @Test
    void changeUnit017_createsFeedbackIndexes() {
        List<Document> indexes = new ArrayList<>();
        mongoTemplate.getCollection("feedbackRequests").listIndexes().forEach(indexes::add);

        assertThat(indexes).anySatisfy(ix -> {
            Document key = ix.get("key", Document.class);
            assertThat(key.containsKey("interviewEventId") && key.containsKey("interviewerMemberId")).isTrue();
            assertThat(ix.getBoolean("unique", false)).isTrue();
        });
        assertThat(indexes).anySatisfy(ix -> {
            Document key = ix.get("key", Document.class);
            assertThat(key.containsKey("tokenHash")).isTrue();
            assertThat(ix.getBoolean("unique", false)).isTrue();
            assertThat(ix.containsKey("partialFilterExpression")).isTrue();
        });
        assertThat(indexes).anySatisfy(ix -> {
            Document key = ix.get("key", Document.class);
            assertThat(key.containsKey("status") && key.containsKey("nextReminderDueAt")).isTrue();
        });
        // Pre-declared by ChangeUnit001 (F00.1) — present, not recreated.
        assertThat(indexes).anySatisfy(ix -> {
            Document key = ix.get("key", Document.class);
            assertThat(key.containsKey("interviewEventId") && key.containsKey("submittedAt")).isTrue();
        });
    }
}
