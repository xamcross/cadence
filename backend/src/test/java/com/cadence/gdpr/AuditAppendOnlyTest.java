package com.cadence.gdpr;

import com.cadence.BaseIntegrationTest;
import com.cadence.repository.CandidateAuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T021 / FR-014/FR-015 / SC-007 / SC-016: the candidate audit log is append-only by construction and
 * F04 ships no HTTP candidate-create endpoint.
 */
class AuditAppendOnlyTest extends BaseIntegrationTest {

    @Autowired RequestMappingHandlerMapping mapping;

    @Test
    void auditRepository_declaresNoMutationMethod() {
        for (Method m : CandidateAuditEventRepository.class.getMethods()) {
            assertThat(m.getName())
                .withFailMessage("audit repo must be append-only; found %s", m.getName())
                .doesNotStartWith("delete").doesNotStartWith("remove").doesNotStartWith("update").doesNotStartWith("save");
        }
    }

    @Test
    void noMutatingVerbMapsToAnAuditPath_andNoCreateEndpoint() {
        mapping.getHandlerMethods().forEach((info, handler) -> {
            Set<String> patterns = patternsOf(info);
            Set<String> methods = info.getMethodsCondition().getMethods().stream()
                .map(Enum::name).collect(java.util.stream.Collectors.toSet());
            // No DELETE/PUT/PATCH on any /audit path.
            boolean audit = patterns.stream().anyMatch(p -> p.contains("/audit"));
            if (audit) {
                assertThat(methods).doesNotContain("DELETE", "PUT", "PATCH");
            }
            // No POST candidate-create endpoint (creation is the CandidateService.create seam).
            boolean createRoute = patterns.stream().anyMatch(p -> p.equals("/api/internal/candidates"));
            if (createRoute) {
                assertThat(methods).withFailMessage("F04 must ship no candidate-create endpoint")
                    .doesNotContain("POST");
            }
        });
    }

    private Set<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return info.getPatternsCondition() == null ? Set.of() : info.getPatternsCondition().getPatterns();
    }
}
