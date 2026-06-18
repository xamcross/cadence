package com.cadence.ats;

import com.cadence.domain.AtsConnectionStatus;
import com.cadence.domain.AtsWriteBackStatus;
import com.cadence.domain.AtsWriteBackType;
import com.cadence.integration.AtsProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F41 US4 provider isolation (SC-014/FR-022): a Lever outage does not stall a coexisting healthy Greenhouse
 * connection, and a Lever auth failure flips ONLY the Lever connection — never the Greenhouse one (the
 * confused-deputy NEEDS_REAUTH fix).
 */
class AtsProviderIsolationIT extends AtsItBase {

    private static final String WS = "ws-iso";
    private static final Instant EVENT_AT = Instant.parse("2026-07-01T10:00:00Z");

    @Test
    void leverOutageDoesNotStallGreenhouseSyncOrWriteBack() {
        connect(WS);
        connectLever(WS);
        // Lever errors on every call; Greenhouse is healthy.
        leverStub.program("GET", "/v1/opportunities", 503);
        leverStub.program("POST", "/notes", 503);
        stub.addCandidate("gh-1", "GH", "Cand", "gh@example.com", "1", "ghjob", "GH Eng", "Screen");

        // Greenhouse sync still imports while Lever fails.
        sync(WS);
        syncLever(WS); // fails (records FAILED), must not throw / must not affect Greenhouse
        assertThat(candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(WS, AtsProvider.GREENHOUSE, "gh-1"))
            .isPresent();
        assertThat(connectionService.health(WS, AtsProvider.GREENHOUSE).status())
            .isEqualTo(AtsConnectionStatus.CONNECTED);

        // A Greenhouse write-back still delivers while Lever is down.
        String ghId = candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(WS, AtsProvider.GREENHOUSE, "gh-1")
            .orElseThrow().getId();
        writeBackService.enqueue(WS, ghId, AtsWriteBackType.CONFIRMED, EVENT_AT);
        writeBackScheduler.drain();
        assertThat(stub.notes("gh-1")).hasSize(1);
    }

    @Test
    void leverAuthFailureFlipsOnlyTheLeverConnection() {
        connect(WS);
        connectLever(WS);
        String leverId = importLeverCandidate("lv-1");

        // Lever notes endpoint rejects the credential (401) -> the write-back delivery hits an AUTH failure.
        leverStub.program("POST", "/notes", 401);
        writeBackService.enqueue(WS, leverId, AtsWriteBackType.CONFIRMED, EVENT_AT);
        writeBackScheduler.drain();

        // Confused-deputy fix: ONLY the Lever connection flips to NEEDS_REAUTH; Greenhouse stays CONNECTED.
        assertThat(connectionService.health(WS, AtsProvider.LEVER).status())
            .isEqualTo(AtsConnectionStatus.NEEDS_REAUTH);
        assertThat(connectionService.health(WS, AtsProvider.GREENHOUSE).status())
            .isEqualTo(AtsConnectionStatus.CONNECTED);
        // The write-back is HELD (recoverable creds issue), not dead-lettered.
        assertThat(writeBacks.findAll().get(0).getStatus()).isEqualTo(AtsWriteBackStatus.PENDING);
    }

    @Test
    void leverWriteBackHeldWhileConnectionNotReadyThenDeliversOnReconnect() {
        // SC-004 (the cross-drain hold->recover path): a Lever write-back whose connection is not deliverable is
        // HELD across drains (PENDING, retry budget untouched), never dead-lettered, and delivers once the Lever
        // connection recovers — while a coexisting Greenhouse connection is irrelevant to it.
        connect(WS); // Greenhouse present (coexistence) and untouched throughout
        connectLever(WS);
        String leverId = importLeverCandidate("lv-1");
        writeBackService.enqueue(WS, leverId, AtsWriteBackType.CONFIRMED, EVENT_AT);

        // Flip ONLY the Lever connection to NEEDS_REAUTH -> the write-back is not deliverable yet.
        mongoTemplate.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("workspaceId").is(WS)
                    .and("provider").is(AtsProvider.LEVER)),
            new org.springframework.data.mongodb.core.query.Update().set("status", AtsConnectionStatus.NEEDS_REAUTH),
            com.cadence.domain.AtsConnection.class);
        for (int i = 0; i < 3; i++) {
            writeBackScheduler.drain();
        }
        var held = writeBacks.findAll().get(0);
        assertThat(held.getStatus()).isEqualTo(AtsWriteBackStatus.PENDING);   // held, not dead-lettered
        assertThat(held.getAttemptCount()).isLessThanOrEqualTo(0);            // budget not consumed by the hold
        assertThat(leverStub.notes("lv-1")).isEmpty();

        // Recovery: re-connect Lever -> the held write-back now delivers.
        connectLever(WS);
        writeBackScheduler.drain();
        assertThat(writeBacks.findAll().get(0).getStatus()).isEqualTo(AtsWriteBackStatus.DELIVERED);
        assertThat(leverStub.notes("lv-1")).hasSize(1);
    }

    private String importLeverCandidate(String ref) {
        leverStub.addOpportunity(ref, "LV Cand", ref + "@example.com", "2", "lvjob", "LV Eng", "Phone");
        syncLever(WS);
        return candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(WS, AtsProvider.LEVER, ref)
            .orElseThrow().getId();
    }
}
