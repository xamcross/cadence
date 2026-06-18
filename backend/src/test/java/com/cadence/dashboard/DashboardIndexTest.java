package com.cadence.dashboard;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F50 T012 — ChangeUnit021 created both dashboard indexes on {@code schedulingRequests}
 * ({@code {workspaceId,status,bookedAt}} and {@code {workspaceId,status,bookedStartAt}}).
 */
class DashboardIndexTest extends BaseIntegrationTest {

    @Test
    void changeUnit021_createsBothDashboardIndexes() {
        List<Document> keys = new ArrayList<>();
        for (Document idx : mongoTemplate.getCollection("schedulingRequests").listIndexes()) {
            keys.add((Document) idx.get("key"));
        }
        assertThat(keys).contains(
            new Document("workspaceId", 1).append("status", 1).append("bookedAt", 1));
        assertThat(keys).contains(
            new Document("workspaceId", 1).append("status", 1).append("bookedStartAt", 1));
    }
}
