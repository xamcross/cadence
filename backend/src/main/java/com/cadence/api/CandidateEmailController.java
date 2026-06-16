package com.cadence.api;

import com.cadence.api.EmailDeliveryDtos.SendRequest;
import com.cadence.api.EmailDeliveryDtos.SendResponse;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailMessageType;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.EmailDispatchService.DispatchResult;
import com.cadence.service.SessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

/**
 * The recruiter candidate-send trigger (F22, contract A) — {@code POST /api/internal/candidates/{id}/emails}.
 * Enqueues an immediate {@link EmailDispatchService} dispatch (the same gated, idempotent path F13/F23/F31/F32
 * will call programmatically). Class-level {@code @PreAuthorize} is the single role source of truth (satisfies
 * the F02 RbacEndpointInventoryTest). The candidate is workspace-scoped (a foreign/missing candidate ->
 * {@link RbacExceptions.ScopedNotFoundException} -> 404, oracle-free); the consent gate may refuse (409,
 * value-free reason). The response carries ids + status + type only — never recipient/subject/body; no-store.
 */
@RestController
@RequestMapping("/api/internal/candidates/{candidateId}/emails")
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
public class CandidateEmailController {

    private final EmailDispatchService dispatch;
    private final Clock clock;

    public CandidateEmailController(EmailDispatchService dispatch, Clock clock) {
        this.dispatch = dispatch;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<SendResponse> send(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String candidateId,
            @RequestBody(required = false) SendRequest req) {
        if (req == null || req.messageType() == null || req.messageType().isBlank()) {
            throw new EmailDeliveryExceptions.InvalidRequestException("messageType is required.");
        }
        EmailMessageType type = parseType(req.messageType());

        DispatchResult result = dispatch.enqueue(
            principal.workspaceId(), candidateId, type, req.stageKey(),
            Instant.now(clock), req.sampleValues(), null);

        if (result.status() == DispatchStatus.REFUSED) {
            throw new EmailDeliveryExceptions.NotContactableException(result.reason().name());
        }

        SendResponse body = SendResponse.from(result, type.name());
        // 200 for an idempotent duplicate of an already-sent message; 202 for a fresh enqueue/send.
        HttpStatus status = result.idempotentDuplicate() && result.status() == DispatchStatus.SENT
            ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    /** A FAILED terminal (render/provider/transport) still returns 202 with the status — the recruiter sees it. */
    private EmailMessageType parseType(String raw) {
        try {
            return EmailMessageType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new EmailDeliveryExceptions.InvalidRequestException("Unknown messageType.");
        }
    }
}
