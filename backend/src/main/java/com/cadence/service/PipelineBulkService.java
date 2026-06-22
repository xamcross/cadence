package com.cadence.service;

import com.cadence.api.PipelineDtos.BulkAction;
import com.cadence.api.PipelineDtos.BulkRequest;
import com.cadence.api.PipelineDtos.BulkResponse;
import com.cadence.api.PipelineDtos.BulkResult;
import com.cadence.api.PipelineExceptions;
import com.cadence.config.PipelineProperties;
import com.cadence.domain.EmailMessageType;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F51 bulk actions — a per-candidate fan-out over the existing single-candidate send seams (FR-015..FR-020). Each
 * candidate is processed independently (one failure never aborts the batch). A synchronous {@link ContactPermissionGate}
 * pre-check yields a single coarse {@code not_contactable} skip; EVERY deny cause and EVERY thrown exception
 * (including the member-id-bearing {@code UnschedulableRequiredException}) collapses to that one byte-identical
 * outcome (no GDPR/existence oracle — FR-018). The authoritative 0-send guarantee is the asynchronous send-time gate
 * inside {@link EmailDispatchService}/{@link SchedulingService} (the TOCTOU backstop, SC-006); the sync pre-check is
 * only for immediate, honest recruiter feedback.
 */
@Service
public class PipelineBulkService {

    private static final Logger log = LoggerFactory.getLogger(PipelineBulkService.class);
    private static final String NOT_CONTACTABLE = "not_contactable";

    private final Clock clock;
    private final ContactPermissionGate gate;
    private final EmailDispatchService dispatch;
    private final SchedulingService scheduling;
    private final PipelineProperties props;

    public PipelineBulkService(Clock clock, ContactPermissionGate gate, EmailDispatchService dispatch,
                               SchedulingService scheduling, PipelineProperties props) {
        this.clock = clock;
        this.gate = gate;
        this.dispatch = dispatch;
        this.scheduling = scheduling;
        this.props = props;
    }

    public BulkResponse execute(String workspaceId, String actorMemberId, BulkRequest req, String ip) {
        if (req == null || req.action() == null || req.candidateIds() == null || req.candidateIds().isEmpty()) {
            throw new PipelineExceptions.InvalidRequestException();
        }
        // FR-020: enforce the cap BEFORE touching any candidate.
        if (req.candidateIds().size() > props.getBulkMax()) {
            throw new PipelineExceptions.SelectionTooLargeException();
        }
        // Validate the per-verb requirements up front (no partial fan-out on a malformed request).
        EmailMessageType updateType = null;
        if (req.action() == BulkAction.SEND_SCHEDULING_LINK) {
            if (req.templateId() == null || req.templateId().isBlank()) {
                throw new PipelineExceptions.InvalidRequestException();
            }
        } else { // SEND_UPDATE_EMAIL
            updateType = resolveUpdateType(req.messageType());
        }

        Instant now = Instant.now(clock);
        List<BulkResult> results = new ArrayList<>(req.candidateIds().size());
        for (String candidateId : req.candidateIds()) {
            // Synchronous gate pre-check — collapse ALL deny causes to the single coarse reason (no oracle).
            if (!gate.evaluate(workspaceId, candidateId).permit()) {
                results.add(new BulkResult(candidateId, "SKIPPED", NOT_CONTACTABLE));
                continue;
            }
            try {
                if (req.action() == BulkAction.SEND_UPDATE_EMAIL) {
                    dispatch.enqueue(workspaceId, candidateId, updateType, "BASE", now, Map.of(), candidateId);
                } else {
                    scheduling.initiate(workspaceId, actorMemberId, candidateId, req.templateId(),
                        req.locationText(), req.rangeStart(), req.rangeEnd(), ip);
                }
                results.add(new BulkResult(candidateId, "ENQUEUED", null));
            } catch (RuntimeException e) {
                // EVERY failure -> the same coarse skip (the exception payload, e.g. UnschedulableRequired member
                // ids, is DISCARDED — never surfaced). Log the cause class only (value-free).
                log.info("pipeline bulk per-candidate skipped {} {}",
                    StructuredArguments.kv("workspaceId", workspaceId),
                    StructuredArguments.kv("cause", e.getClass().getSimpleName()));
                results.add(new BulkResult(candidateId, "SKIPPED", NOT_CONTACTABLE));
            }
        }
        return new BulkResponse(results);
    }

    /** Only holding/update message types are permitted for the bulk update verb; default HOLD_UPDATE. */
    private EmailMessageType resolveUpdateType(String raw) {
        if (raw == null || raw.isBlank()) {
            return EmailMessageType.HOLD_UPDATE;
        }
        EmailMessageType type;
        try {
            type = EmailMessageType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PipelineExceptions.InvalidRequestException();
        }
        if (type != EmailMessageType.HOLD_UPDATE) {
            throw new PipelineExceptions.InvalidRequestException();
        }
        return type;
    }
}
