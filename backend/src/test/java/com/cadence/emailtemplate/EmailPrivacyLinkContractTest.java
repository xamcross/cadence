package com.cadence.emailtemplate;

import com.cadence.config.AuthProperties;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.MergeToken;
import com.cadence.domain.RenderedMessage;
import com.cadence.service.EmailTemplateService;
import com.cadence.service.MergeTokenCatalogue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T017 / contract C-LINK-4 / SC-010 (GDPR Art. 14, FR-020): every candidate-facing built-in email template
 * that flows through {@link EmailTemplateService#renderForSend} renders the Privacy Notice link as a real
 * {@code <a href="...<spaBaseUrl>.../privacy">} anchor - never the literal {@code {{privacy_link}}} nor the
 * {@code [[missing:privacy_link]]} marker - and the URL carries no candidate token or PII. Also asserts that
 * {@code MergeTokenCatalogue.isPermitted(type, PRIVACY_LINK)} holds for every such type, so a future type
 * cannot silently drop the link. Asserts against the CONFIGURED {@code spaBaseUrl} (test default
 * {@code http://localhost:4200}), NOT a hardcoded https origin.
 *
 * <p>The F21 {@code BuiltInTemplateCompletenessTest} stays green because the catalogue now permits
 * PRIVACY_LINK for every type (it is universal), so every built-in body still validates.
 */
class EmailPrivacyLinkContractTest extends EmailTemplateItBase {

    @Autowired EmailTemplateService service;
    @Autowired MergeTokenCatalogue catalogue;
    @Autowired AuthProperties authProps;

    private static final String CANDIDATE_ID = "cand-privacy-1";
    // A PII sentinel: a name + an email-shaped token that MUST NOT appear in the privacy_link href.
    private static final String CANDIDATE_NAME = "Dana SENTINELPII dana.sentinel@example.com";

    @Test
    void everyCandidateFacingTemplate_rendersPrivacyAnchor_withNoPii() {
        seedCandidate(WS, CANDIDATE_ID, CANDIDATE_NAME);

        String expectedUrl = authProps.getSpaBaseUrl() + "/privacy";
        String expectedAnchor = "<a href=\"" + expectedUrl + "\">" + expectedUrl + "</a>";

        for (EmailMessageType type : EmailMessageType.values()) {
            // The token MUST be permitted for every candidate-facing type (else it renders the literal token).
            assertThat(catalogue.isPermitted(type, MergeToken.PRIVACY_LINK))
                .as("PRIVACY_LINK permitted for %s", type).isTrue();

            // Supply a permissive non-PII context for the other tokens so nothing else goes missing; the
            // privacy_link value is injected centrally and MUST NOT be overridable by this context.
            Map<String, String> ctx = new HashMap<>();
            ctx.put("stage_name", "Onsite");
            ctx.put("interview_date", "Mon 5 Jan");
            ctx.put("interview_time", "10:00");
            ctx.put("time_zone", "Europe/London");
            ctx.put("location", "HQ");
            ctx.put("expected_date", "Fri");
            ctx.put("scheduling_link", "https://example.test/schedule");
            ctx.put("status_link", "https://example.test/status");
            ctx.put("reschedule_link", "https://example.test/reschedule");
            ctx.put("confirm_link", "https://example.test/confirm");
            ctx.put("feedback_link", "https://example.test/feedback");
            // An attempted override of the central constant must be ignored.
            ctx.put("privacy_link", "https://evil.test/leak?token=SENTINELPII");

            RenderedMessage m = service.renderForSend(WS, type, "BASE", CANDIDATE_ID, ctx);

            assertThat(m.bodyHtml())
                .as("%s body must contain the rendered Privacy anchor", type)
                .contains(expectedAnchor);
            assertThat(m.bodyHtml())
                .as("%s body must not contain the literal {{privacy_link}} token", type)
                .doesNotContain("{{privacy_link}}");
            assertThat(m.bodyHtml())
                .as("%s body must not contain the [[missing:privacy_link]] marker", type)
                .doesNotContain("[[missing:privacy_link]]");
            assertThat(m.missingFields())
                .as("%s privacy_link must not be reported missing", type)
                .doesNotContain("privacy_link");

            // The Privacy URL is a constant - no candidate token / PII (SC-010). The override attempt above
            // must have been ignored; the rendered anchor is exactly the configured /privacy URL.
            assertThat(expectedUrl)
                .as("the Privacy URL itself must carry no candidate PII or token")
                .doesNotContain("SENTINELPII")
                .doesNotContain("token=")
                .doesNotContain("dana.sentinel@example.com")
                .endsWith("/privacy");
            // The leaked override URL must NOT appear (central injection wins over caller context).
            assertThat(m.bodyHtml())
                .as("%s must not render a caller-supplied privacy_link override", type)
                .doesNotContain("evil.test")
                .doesNotContain("token=SENTINELPII");
        }
    }
}
