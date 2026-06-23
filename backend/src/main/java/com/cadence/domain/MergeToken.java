package com.cadence.domain;

import java.util.Optional;

/**
 * The fixed, add-only merge-token catalogue (F21 D5, data-model §3). Each token has a wire name
 * ({@code {{token}}}) and a {@code urlTyped} flag — URL-typed tokens are system-produced and render as
 * an anchor whose href and visible text are the same scheme-checked URL (D3, FR-016).
 */
public enum MergeToken {
    CANDIDATE_NAME("candidate_name", false),
    RECRUITER_NAME("recruiter_name", false),
    WORKSPACE_NAME("workspace_name", false),
    STAGE_NAME("stage_name", false),
    INTERVIEW_DATE("interview_date", false),
    INTERVIEW_TIME("interview_time", false),
    TIME_ZONE("time_zone", false),
    LOCATION("location", false),
    SCHEDULING_LINK("scheduling_link", true),
    STATUS_LINK("status_link", true),
    RESCHEDULE_LINK("reschedule_link", true),
    FEEDBACK_LINK("feedback_link", true),
    EXPECTED_DATE("expected_date", false),
    // F23 No-Show Defense (append-only). URL-typed — the candidate attendance-confirmation link.
    CONFIRM_LINK("confirm_link", true),
    // F31.x Terms/Privacy (append-only, GDPR Art. 14). URL-typed - the public Privacy Notice link
    // (<spaBaseUrl>/privacy). Its constant value is injected centrally in EmailTemplateService.renderForSend,
    // never per call-site, so it carries no candidate token or PII (contract C-LINK-4, research D8).
    PRIVACY_LINK("privacy_link", true);

    private final String token;
    private final boolean urlTyped;

    MergeToken(String token, boolean urlTyped) {
        this.token = token;
        this.urlTyped = urlTyped;
    }

    public String token() { return token; }
    public boolean isUrlTyped() { return urlTyped; }

    public static Optional<MergeToken> byToken(String token) {
        for (MergeToken t : values()) {
            if (t.token.equals(token)) return Optional.of(t);
        }
        return Optional.empty();
    }
}
