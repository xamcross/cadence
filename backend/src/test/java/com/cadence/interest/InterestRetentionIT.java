package com.cadence.interest;

import com.cadence.domain.InterestRequest;
import com.cadence.domain.InterestRequestStatus;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.scheduler.InterestRetentionScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T035/SC-008: the retention purge deletes aged rows under the MutableClock; a {@code 0}/unset
 * retentionPeriodDays uses the 180-day fallback (NOT immediate delete); only {@code isConfigured()} workspaces
 * are scanned; double-sweep is idempotent.
 */
class InterestRetentionIT extends InterestItBase {

    @Autowired InterestRetentionScheduler scheduler;

    private InterestRequest seedAged(String email, long daysAgo) {
        InterestRequest r = new InterestRequest();
        r.setWorkspaceId(WS);
        r.setName("Dana");
        r.setEmail(email);
        r.setEmailHash(crypto.emailHash(email));
        r.setOpenEmailHash(crypto.emailHash(email));
        r.setStatus(InterestRequestStatus.NEW);
        Instant when = Instant.now(clock).minus(Duration.ofDays(daysAgo));
        r.setSubmittedAt(when);
        r.setUpdatedAt(when);
        return mongoTemplate.save(r);
    }

    @Test
    void purge_deletesAged_underExplicitPeriod_keepsRecent() {
        configuredWorkspace(30);
        seedAged("old@example.com", 40);    // older than 30d -> purged
        seedAged("recent@example.com", 5);  // within 30d -> kept

        scheduler.scheduled();

        assertThat(interestRepo.findAll()).hasSize(1);
        assertThat(interestRepo.findAll().get(0).getEmail()).isEqualTo("recent@example.com");
    }

    @Test
    void unsetRetentionPeriod_usesFallback_notImmediateDelete() {
        configuredWorkspace(0); // 0 == unset -> 180-day fallback
        seedAged("young@example.com", 1);   // 1 day old -> kept (NOT deleted)
        seedAged("ancient@example.com", 200); // > 180d fallback -> purged

        scheduler.scheduled();

        assertThat(interestRepo.findAll()).hasSize(1);
        assertThat(interestRepo.findAll().get(0).getEmail()).isEqualTo("young@example.com");
    }

    @Test
    void onlyConfiguredWorkspacesScanned() {
        // An UNconfigured workspace config (configuredAt == null) -> not scanned -> its aged row survives.
        WorkspaceConfig unconfigured = new WorkspaceConfig();
        unconfigured.setWorkspaceId(WS);
        unconfigured.setName("Pending");
        unconfigured.setRetentionPeriodDays(30);
        // configuredAt left null -> isConfigured() == false
        mongoTemplate.save(unconfigured);
        seedAged("survivor@example.com", 100);

        scheduler.scheduled();

        assertThat(interestRepo.findAll()).hasSize(1);
    }

    @Test
    void doubleSweep_isIdempotent() {
        configuredWorkspace(30);
        seedAged("old@example.com", 40);
        seedAged("recent@example.com", 5);

        scheduler.scheduled();
        scheduler.scheduled(); // replay proxy — no further effect

        assertThat(interestRepo.findAll()).hasSize(1);
    }
}
