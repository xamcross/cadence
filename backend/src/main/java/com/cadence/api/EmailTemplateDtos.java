package com.cadence.api;

import com.cadence.domain.RenderedMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * F21 request/response DTOs (contract §A/§B/§C). Responses may carry subject/body on the management read
 * model (the recruiter who manages it may see it) but it is NEVER logged or audited. No member email/name.
 */
public final class EmailTemplateDtos {

    private EmailTemplateDtos() {}

    /** Create/replace the override at {@code stageKey} (default "BASE"). {@code expectedVersion} null for a first edit. */
    public record EditRequest(String stageKey, String subject, String body, Long expectedVersion) {}

    public record ApplyToneRequest(String stageKey, String tone, Long expectedVersion) {}

    public record ResetRequest(String stageKey, Long expectedVersion) {}

    public record LockRequest(String stageKey, Long expectedVersion) {}

    /** Either {@code candidateId} (workspace-scoped) OR {@code sampleValues}; {@code tone} previews an un-applied preset. */
    public record PreviewRequest(String stageKey, String tone, String candidateId, Map<String, String> sampleValues) {}

    public record TemplateResponse(
        String messageType,
        String stageKey,
        String subject,
        String body,
        boolean locked,
        Long version,
        String source,                 // BUILTIN | OVERRIDE
        List<String> permittedTokens,
        String updatedByMemberId,
        Instant updatedAt) {}

    public record ListResponse(List<TemplateResponse> templates) {}

    /** The render result (FR-019) — F22 + the preview UI depend on this shape. */
    public record RenderedMessageResponse(String subject, String bodyText, String bodyHtml, List<String> missingFields) {
        public static RenderedMessageResponse from(RenderedMessage m) {
            return new RenderedMessageResponse(m.subject(), m.bodyText(), m.bodyHtml(), m.missingFields());
        }
    }
}
