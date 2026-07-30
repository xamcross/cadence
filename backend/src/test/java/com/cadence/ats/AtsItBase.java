package com.cadence.ats;

import com.cadence.BaseIntegrationTest;
import com.cadence.domain.AtsConnection;
import com.cadence.domain.AtsSyncRun;
import com.cadence.domain.AtsWriteBack;
import com.cadence.domain.Candidate;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.integration.AtsProvider;
import com.cadence.repository.AtsConnectionRepository;
import com.cadence.repository.AtsWriteBackRepository;
import com.cadence.repository.CandidateRepository;
import com.cadence.scheduler.AtsSyncScheduler;
import com.cadence.scheduler.AtsWriteBackScheduler;
import com.cadence.service.AtsConnectionService;
import com.cadence.service.AtsSyncService;
import com.cadence.service.AtsWriteBackService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared fixture for the F40 ATS tests. Points {@code cadence.ats.greenhouse.base-url} at the
 * {@link StubGreenhouse} (integration-pending), zeroes the retry backoff, and cleans the ATS collections +
 * candidates per test (remove, NEVER dropCollection — would drop the Mongock 018 indexes).
 */
abstract class AtsItBase extends BaseIntegrationTest {

    protected static final StubGreenhouse stub = new StubGreenhouse();
    protected static final StubLever leverStub = new StubLever();

    @DynamicPropertySource
    static void atsProps(DynamicPropertyRegistry r) {
        r.add("cadence.ats.greenhouse.base-url", stub::baseUrl);
        r.add("cadence.ats.lever.base-url", leverStub::baseUrl);
        r.add("cadence.ats.retry-base-backoff", () -> "PT0S");
    }

    // The stub is a JVM-lifetime singleton (the Mongo-container / StubGoogleCalendar pattern): NEVER stopped in
    // an @AfterAll, or a second IT class reusing the cached Spring context would hit a dead port. Reaped at JVM exit.

    @Autowired protected AtsConnectionService connectionService;
    @Autowired protected AtsSyncService syncService;
    @Autowired protected AtsSyncScheduler syncScheduler;
    @Autowired protected AtsWriteBackService writeBackService;
    @Autowired protected AtsWriteBackScheduler writeBackScheduler;
    @Autowired protected AtsConnectionRepository connections;
    @Autowired protected AtsWriteBackRepository writeBacks;
    @Autowired protected CandidateRepository candidates;

    @BeforeEach
    void cleanAts() {
        stub.reset();
        leverStub.reset();
        mongoTemplate.remove(new Query(), AtsConnection.class);
        mongoTemplate.remove(new Query(), AtsWriteBack.class);
        mongoTemplate.remove(new Query(), AtsSyncRun.class);
        mongoTemplate.remove(new Query(), Candidate.class);
        mongoTemplate.remove(new Query(), WorkspaceEntitlement.class);
    }

    /**
     * 032 T7: seed a Team entitlement for a workspace so the ATS_INTEGRATIONS gate does not block these
     * pre-existing F40/F41 fixtures (which predate billing and never modeled a plan). Idempotent per workspace
     * (some tests connect Greenhouse then Lever for the SAME workspace, which would otherwise double-insert
     * against the unique {@code workspaceId} index).
     */
    protected void seedTeamEntitlement(String workspaceId) {
        if (mongoTemplate.exists(Query.query(Criteria.where("workspaceId").is(workspaceId)), WorkspaceEntitlement.class)) {
            return;
        }
        WorkspaceEntitlement e = new WorkspaceEntitlement();
        e.setWorkspaceId(workspaceId);
        e.setFsLicenseId("lic-" + workspaceId);
        e.setFsPlanId("2002");
        mongoTemplate.insert(e);
    }

    /** Connect a workspace against the Greenhouse stub (verifies via GET /v1/jobs -> 200) and return the CONNECTED row. */
    protected AtsConnection connect(String workspaceId) {
        seedTeamEntitlement(workspaceId);
        connectionService.connect(workspaceId, AtsProvider.GREENHOUSE, "test-key-" + workspaceId);
        return connections.findByWorkspaceIdAndProvider(workspaceId, AtsProvider.GREENHOUSE).orElseThrow();
    }

    /** Connect a workspace against the Lever stub and return the CONNECTED row. */
    protected AtsConnection connectLever(String workspaceId) {
        seedTeamEntitlement(workspaceId);
        connectionService.connect(workspaceId, AtsProvider.LEVER, "lever-key-" + workspaceId);
        return connections.findByWorkspaceIdAndProvider(workspaceId, AtsProvider.LEVER).orElseThrow();
    }

    /** Run one inbound sync for the workspace's Greenhouse connection. */
    protected void sync(String workspaceId) {
        syncService.syncWorkspace(
            connections.findByWorkspaceIdAndProvider(workspaceId, AtsProvider.GREENHOUSE).orElseThrow());
    }

    /** Run one inbound sync for the workspace's Lever connection. */
    protected void syncLever(String workspaceId) {
        syncService.syncWorkspace(
            connections.findByWorkspaceIdAndProvider(workspaceId, AtsProvider.LEVER).orElseThrow());
    }
}
