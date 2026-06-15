package com.cadence.gdpr;

import com.cadence.auth.AuthTestConfig;
import com.cadence.domain.Candidate;
import com.cadence.domain.CheckpointStatus;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.SchedulerCheckpoint;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.scheduler.RetentionScanTask;
import com.cadence.scheduler.SchedulerCheckpointService;
import com.cadence.service.ContactPermissionGate;
import com.cadence.service.ContactPermissionGate.Reason;
import com.cadence.service.RetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** T041 / US5 / SC-009/SC-014: retention scan boundary, gate-deny, guarded delete, flag-clear, missed-fire. */
class RetentionIntegrationTest extends GdprItBase {

    @Autowired RetentionService retention;
    @Autowired ContactPermissionGate gate;
    @Autowired SchedulerCheckpointService checkpoints;

    private static final int RETENTION_DAYS = 30;

    private void configureRetention(int days) {
        WorkspaceConfig cfg = configRepoFind();
        if (cfg == null) {
            cfg = new WorkspaceConfig();
            cfg.setWorkspaceId(WS);
            cfg.setConfiguredAt(AuthTestConfig.FIXED_START);
            cfg.setRetentionAcknowledgedAt(AuthTestConfig.FIXED_START);
        }
        cfg.setRetentionPeriodDays(days);
        mongoTemplate.save(cfg);
    }

    private WorkspaceConfig configRepoFind() {
        return mongoTemplate.findOne(Query.query(Criteria.where("workspaceId").is(WS)), WorkspaceConfig.class);
    }

    private void setLastContact(String id, Instant when) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
            new Update().set("lastContactAt", when), Candidate.class);
    }

    @Test
    void scan_flagsStrictlyOverAge_notAtBoundary() {
        configureRetention(RETENTION_DAYS);
        Candidate over = seedCandidate("Over", "over@example.com", "+15550000050");
        Candidate atBoundary = seedCandidate("Edge", "edge@example.com", "+15550000051");
        setLastContact(over.getId(), AuthTestConfig.FIXED_START.minus(Duration.ofDays(RETENTION_DAYS + 1)));
        setLastContact(atBoundary.getId(), AuthTestConfig.FIXED_START.minus(Duration.ofDays(RETENTION_DAYS)));

        retention.scan(WS);

        assertThat(mongoTemplate.findById(over.getId(), Candidate.class).isRetentionFlagged()).isTrue();
        assertThat(mongoTemplate.findById(atBoundary.getId(), Candidate.class).isRetentionFlagged()).isFalse();
    }

    @Test
    void flaggedCandidate_gateDeniesOverRetention_andDeleteIsGuarded() {
        configureRetention(RETENTION_DAYS);
        Candidate over = seedCandidate("Flag", "flag@example.com", "+15550000052");
        Candidate young = seedCandidate("Young", "young@example.com", "+15550000053");
        setLastContact(over.getId(), AuthTestConfig.FIXED_START.minus(Duration.ofDays(40)));
        retention.scan(WS);

        assertThat(gate.evaluate(WS, over.getId()).reason()).isEqualTo(Reason.OVER_RETENTION);

        // Delete is guarded: an unflagged active candidate is NOT wiped.
        assertThat(retention.confirmDelete(WS, young.getId(), "admin1")).isFalse();
        assertThat(mongoTemplate.findById(young.getId(), Candidate.class).getErasureState())
            .isEqualTo(com.cadence.domain.ErasureState.ACTIVE);

        // A flagged candidate is wiped on Admin confirm.
        assertThat(retention.confirmDelete(WS, over.getId(), "admin1")).isTrue();
        assertThat(mongoTemplate.findById(over.getId(), Candidate.class).getErasureState())
            .isEqualTo(com.cadence.domain.ErasureState.ERASED);
    }

    @Test
    void lengtheningPeriod_clearsStaleFlag_gatePermitsIffBasisRecorded() {
        configureRetention(RETENTION_DAYS);
        Candidate c = seedCandidateWithBasis("Keep", "keep@example.com", "+15550000054");
        setLastContact(c.getId(), AuthTestConfig.FIXED_START.minus(Duration.ofDays(40)));
        retention.scan(WS);
        assertThat(mongoTemplate.findById(c.getId(), Candidate.class).isRetentionFlagged()).isTrue();

        // Lengthen the period so 40 days < 90 days -> next scan clears the flag.
        configureRetention(90);
        retention.scan(WS);
        Candidate after = mongoTemplate.findById(c.getId(), Candidate.class);
        assertThat(after.isRetentionFlagged()).isFalse();
        // Basis was recorded, so the gate now permits.
        assertThat(gate.evaluate(WS, c.getId()).permit()).isTrue();
    }

    @Test
    void clearedFlag_withNoBasis_gateDeniesNoBasisNotPermit() {
        configureRetention(RETENTION_DAYS);
        Candidate c = seedCandidate("NoBasis", "nb@example.com", "+15550000055");
        setLastContact(c.getId(), AuthTestConfig.FIXED_START.minus(Duration.ofDays(40)));
        retention.scan(WS);
        configureRetention(90);
        retention.scan(WS);
        assertThat(gate.evaluate(WS, c.getId()).reason()).isEqualTo(Reason.NO_BASIS);
    }

    @Test
    void missedFire_isReplayedViaStaleRunningCheckpoint() {
        configureRetention(RETENTION_DAYS);
        Candidate over = seedCandidate("Replay", "replay@example.com", "+15550000056");
        setLastContact(over.getId(), AuthTestConfig.FIXED_START.minus(Duration.ofDays(40)));

        // A stale RUNNING checkpoint (real-clock startedAt > 15 min ago) simulates a missed fire.
        SchedulerCheckpoint cp = new SchedulerCheckpoint();
        cp.setTaskName(RetentionScanTask.TASK_NAME);
        cp.setStatus(CheckpointStatus.RUNNING);
        cp.setStartedAt(Instant.now().minus(Duration.ofMinutes(20)));
        mongoTemplate.save(cp);

        checkpoints.replayMissedFires();

        // The replay ran the scan -> the over-age candidate is flagged, and the checkpoint completed.
        assertThat(mongoTemplate.findById(over.getId(), Candidate.class).isRetentionFlagged()).isTrue();
        SchedulerCheckpoint after = mongoTemplate.findOne(
            Query.query(Criteria.where("taskName").is(RetentionScanTask.TASK_NAME)), SchedulerCheckpoint.class);
        assertThat(after.getStatus()).isEqualTo(CheckpointStatus.COMPLETED);
    }

    @SuppressWarnings("unused")
    private Optional<LawfulBasis> keep() { return Optional.empty(); }
}
