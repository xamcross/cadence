package com.cadence.feedback;

import com.cadence.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-008(b): the write-only token surface is STRUCTURALLY bounded — the ONLY token-routed handlers under
 * {@code /api/feedback/} are load (GET) and submit (POST). Asserted by a route inventory (the
 * RbacEndpointInventoryTest precedent), so a future edit that adds a token-routed read of scorecard content
 * fails the build.
 */
class FeedbackTokenSurfaceTest extends BaseIntegrationTest {

    @Autowired
    RequestMappingHandlerMapping mapping;

    @Test
    void onlyTwoTokenRoutedHandlers_loadAndSubmit() {
        List<String> tokenHandlers = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (patternsOf(info).stream().anyMatch(p -> p.startsWith("/api/feedback/"))) {
                tokenHandlers.add(handler.getMethod().getName());
            }
        });
        // Exactly the blank-form load + the submit. No token-routed handler returns scorecard content.
        assertThat(tokenHandlers).containsExactlyInAnyOrder("load", "submit");
    }

    private Set<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return info.getPatternsCondition() == null ? Set.of() : info.getPatternsCondition().getPatterns();
    }
}
