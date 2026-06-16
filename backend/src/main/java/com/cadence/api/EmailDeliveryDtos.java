package com.cadence.api;

import com.cadence.service.EmailDispatchService.DispatchResult;

import java.util.Map;

/**
 * F22 recruiter-send request/response DTOs (contract A). The response carries ids + status + reason
 * literal ONLY — never the recipient address, rendered subject, or body (FR-013/SC-006).
 */
public final class EmailDeliveryDtos {

    private EmailDeliveryDtos() {}

    /**
     * Send a templated message to a candidate now. {@code messageType} required (an EmailMessageType);
     * {@code stageKey} optional (default "BASE"); {@code sampleValues} optional non-PII contextual scalars
     * for tokens not derivable from the candidate. The candidate name is resolved server-side, never here.
     */
    public record SendRequest(String messageType, String stageKey, Map<String, String> sampleValues) {}

    /** Value-free dispatch outcome: ids + status + messageType only. */
    public record SendResponse(String dispatchId, String status, String messageType) {
        public static SendResponse from(DispatchResult r, String messageType) {
            return new SendResponse(r.dispatchId(), r.status().name(), messageType);
        }
    }
}
