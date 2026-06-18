package com.cadence.api;

import com.cadence.domain.AtsWriteBack;
import com.cadence.domain.AtsWriteBackStatus;
import com.cadence.integration.AtsProvider;
import com.cadence.repository.AtsSyncRunRepository;
import com.cadence.repository.AtsWriteBackRepository;
import com.cadence.service.AtsConnectionService;
import com.cadence.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F40 ATS connection management + status surface (US1/US2/US4). Connection mutation is Admin-only (FR-004);
 * the read-only health + sync-status are also visible to Recruiter (state + timestamps + counts, NEVER the
 * key). There is NO inbound ingestion endpoint (poll-only, FR-011) — these are the only ATS paths.
 */
@RestController
@RequestMapping("/api/internal/ats")
@PreAuthorize("hasRole('ADMIN')")
public class AtsConnectionController {

    private final AtsConnectionService connections;
    private final AtsSyncRunRepository syncRuns;
    private final AtsWriteBackRepository writeBacks;

    public AtsConnectionController(AtsConnectionService connections, AtsSyncRunRepository syncRuns,
                                   AtsWriteBackRepository writeBacks) {
        this.connections = connections;
        this.syncRuns = syncRuns;
        this.writeBacks = writeBacks;
    }

    @GetMapping("/connection")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
    public AtsDtos.HealthResponse connection(@AuthenticationPrincipal SessionService.Principal principal) {
        AtsConnectionService.Health h = connections.health(principal.workspaceId());
        return toHealth(h);
    }

    @PostMapping("/connection")
    public AtsDtos.HealthResponse connect(@AuthenticationPrincipal SessionService.Principal principal,
                                          @RequestBody AtsDtos.ConnectRequest req) {
        String key = req == null ? null : req.apiKey();
        return toHealth(connections.connect(principal.workspaceId(), AtsProvider.GREENHOUSE, key));
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal SessionService.Principal principal) {
        connections.disconnect(principal.workspaceId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sync-status")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
    public AtsDtos.SyncStatusResponse syncStatus(@AuthenticationPrincipal SessionService.Principal principal) {
        return syncRuns.findFirstByWorkspaceIdOrderByStartedAtDesc(principal.workspaceId())
            .map(r -> new AtsDtos.SyncStatusResponse(r.getFinishedAt(), r.getOutcome(), r.getProcessed(),
                r.getCreated(), r.getUpdated(), r.getSkipped()))
            .orElse(new AtsDtos.SyncStatusResponse(null, null, 0, 0, 0, 0));
    }

    @GetMapping("/dead-letters")
    public List<AtsDtos.DeadLetterEntry> deadLetters(@AuthenticationPrincipal SessionService.Principal principal) {
        return writeBacks.findByWorkspaceIdAndStatus(principal.workspaceId(), AtsWriteBackStatus.DEAD_LETTER)
            .stream()
            .map(this::toEntry)
            .toList();
    }

    private AtsDtos.HealthResponse toHealth(AtsConnectionService.Health h) {
        return new AtsDtos.HealthResponse(h.provider(), h.status(), h.credentialSet(), h.lastVerifiedAt(),
            h.lastSyncAt(), h.degraded(), h.deadLetterCount());
    }

    private AtsDtos.DeadLetterEntry toEntry(AtsWriteBack w) {
        return new AtsDtos.DeadLetterEntry(w.getId(), w.getCandidateId(), w.getType().name(),
            w.getAttemptCount(), w.getLastOutcomeCategory(), w.getUpdatedAt());
    }
}
