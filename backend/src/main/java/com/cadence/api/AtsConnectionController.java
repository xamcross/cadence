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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * F40/F41 ATS connection management + status surface (US1/US2/US4). Connection mutation is Admin-only (FR-004);
 * the read-only health + sync-status are also visible to Recruiter (state + timestamps + counts, NEVER the
 * key). There is NO inbound ingestion endpoint (poll-only, FR-011) — these are the only ATS paths.
 *
 * <p>F41: endpoints are provider-parameterized so a workspace can manage Greenhouse and Lever independently.
 * {@code GET /connections} lists every provider's health; the per-provider paths take a {@code {provider}}
 * segment bound as a String and resolved to {@link AtsProvider} in-controller — an unknown value throws
 * {@link AtsExceptions.InvalidRequestException} (-> 400, no oracle) rather than relying on the enum binder
 * (which would raise {@code MethodArgumentTypeMismatchException} -> the catch-all 500).
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

    /** Health for every provider (the both-providers Admin status surface, SC-011). */
    @GetMapping("/connections")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
    public List<AtsDtos.HealthResponse> connections(@AuthenticationPrincipal SessionService.Principal principal) {
        return connections.listHealth(principal.workspaceId()).stream().map(this::toHealth).toList();
    }

    @GetMapping("/{provider}/connection")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
    public AtsDtos.HealthResponse connection(@AuthenticationPrincipal SessionService.Principal principal,
                                             @PathVariable("provider") String provider) {
        return toHealth(connections.health(principal.workspaceId(), parseProvider(provider)));
    }

    @PostMapping("/{provider}/connection")
    public AtsDtos.HealthResponse connect(@AuthenticationPrincipal SessionService.Principal principal,
                                          @PathVariable("provider") String provider,
                                          @RequestBody AtsDtos.ConnectRequest req) {
        String key = req == null ? null : req.apiKey();
        return toHealth(connections.connect(principal.workspaceId(), parseProvider(provider), key));
    }

    @DeleteMapping("/{provider}/connection")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal SessionService.Principal principal,
                                           @PathVariable("provider") String provider) {
        connections.disconnect(principal.workspaceId(), parseProvider(provider));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{provider}/sync-status")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
    public AtsDtos.SyncStatusResponse syncStatus(@AuthenticationPrincipal SessionService.Principal principal,
                                                 @PathVariable("provider") String provider) {
        return syncRuns.findFirstByWorkspaceIdAndProviderOrderByStartedAtDesc(
                principal.workspaceId(), parseProvider(provider))
            .map(r -> new AtsDtos.SyncStatusResponse(r.getFinishedAt(), r.getOutcome(), r.getProcessed(),
                r.getCreated(), r.getUpdated(), r.getSkipped()))
            .orElse(new AtsDtos.SyncStatusResponse(null, null, 0, 0, 0, 0));
    }

    @GetMapping("/{provider}/dead-letters")
    public List<AtsDtos.DeadLetterEntry> deadLetters(@AuthenticationPrincipal SessionService.Principal principal,
                                                     @PathVariable("provider") String provider) {
        return writeBacks.findByWorkspaceIdAndProviderAndStatus(
                principal.workspaceId(), parseProvider(provider), AtsWriteBackStatus.DEAD_LETTER)
            .stream()
            .map(this::toEntry)
            .toList();
    }

    /** Resolve the {provider} path segment to the enum; unknown -> 400 invalid_request (no 500 oracle). */
    private static AtsProvider parseProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new AtsExceptions.InvalidRequestException();
        }
        try {
            return AtsProvider.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AtsExceptions.InvalidRequestException();
        }
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
