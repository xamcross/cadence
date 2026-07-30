// backend/src/test/java/com/cadence/billing/BillingIndexIT.java
package com.cadence.billing;

import com.cadence.BaseIntegrationTest;
import com.cadence.config.migration.ChangeUnit024_BillingIndexes;
import com.cadence.domain.BillingWebhookEvent;
import com.cadence.domain.EntitlementStatus;
import com.cadence.domain.WorkspaceEntitlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 032 Task 1 -- entitlement/webhook collections, unique indexes, changeset idempotency. */
class BillingIndexIT extends BaseIntegrationTest {

    @BeforeEach
    void clean() {
        mongoTemplate.remove(new Query(), WorkspaceEntitlement.class);
        mongoTemplate.remove(new Query(), BillingWebhookEvent.class);
    }

    private WorkspaceEntitlement entitlement(String ws, String license) {
        WorkspaceEntitlement e = new WorkspaceEntitlement();
        e.setWorkspaceId(ws);
        e.setFsLicenseId(license);
        e.setStatus(EntitlementStatus.ACTIVE);
        e.setBoundAt(Instant.parse("2026-07-30T00:00:00Z"));
        return e;
    }

    @Test
    void uniqueIndexes_exist_onBothCollections() {
        List<IndexInfo> ent = mongoTemplate.indexOps("workspaceEntitlements").getIndexInfo();
        assertThat(ent).anyMatch(i -> i.isUnique() && i.getIndexFields().size() == 1
            && "workspaceId".equals(i.getIndexFields().get(0).getKey()));
        assertThat(ent).anyMatch(i -> i.isUnique() && i.getIndexFields().size() == 1
            && "fsLicenseId".equals(i.getIndexFields().get(0).getKey()));
        List<IndexInfo> evt = mongoTemplate.indexOps("billingWebhookEvents").getIndexInfo();
        assertThat(evt).anyMatch(i -> i.isUnique() && i.getIndexFields().size() == 1
            && "eventId".equals(i.getIndexFields().get(0).getKey()));
    }

    @Test
    void duplicateWorkspace_andDuplicateLicense_rejected() {
        mongoTemplate.insert(entitlement("ws1", "L1"));
        assertThatThrownBy(() -> mongoTemplate.insert(entitlement("ws1", "L2")))
            .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> mongoTemplate.insert(entitlement("ws2", "L1")))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void confersTeam_followsStatusAndExpiry() {
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        WorkspaceEntitlement e = entitlement("ws1", "L1");
        assertThat(e.confersTeam(now)).isTrue();                     // ACTIVE, no expiry (lifetime)
        e.setExpiresAt(now.plusSeconds(60));
        e.setStatus(EntitlementStatus.CANCELLED);
        assertThat(e.confersTeam(now)).isTrue();                     // cancelled but paid period not ended
        e.setExpiresAt(now.minusSeconds(60));
        assertThat(e.confersTeam(now)).isFalse();                    // past effective end
        e.setExpiresAt(now.plusSeconds(60));
        e.setStatus(EntitlementStatus.EXPIRED);
        assertThat(e.confersTeam(now)).isFalse();                    // explicit provider EXPIRED
    }

    @Test
    void changeset_isIdempotentOnReRun() {
        new ChangeUnit024_BillingIndexes().execute(mongoTemplate);   // second run must not throw
    }
}
