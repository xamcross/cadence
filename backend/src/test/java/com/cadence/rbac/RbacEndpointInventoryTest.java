package com.cadence.rbac;

import com.cadence.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-010 (T029): deny-by-default is fail-closed at BUILD time — every internal request handler must
 * declare a minimum role (method- or class-level @PreAuthorize/@PostAuthorize/@Secured). Enumerates
 * all mappings, allow-lists public/framework prefixes by EXCLUSION (so a new internal prefix is still
 * caught), and includes a self-test proving the check's own failure path.
 */
class RbacEndpointInventoryTest extends BaseIntegrationTest {

    private static final List<String> ALLOWED_PREFIXES = List.of(
        "/api/public/", "/api/candidate/", "/actuator", "/oauth2", "/login/oauth2/code", "/error",
        // F22 (research D4): the inbound provider bounce webhook is unauthenticated-by-design — the real
        // gate is the in-controller HMAC signature, not a session/role. Its dedicated permitAll chain
        // (SecurityConfig @Order(3)) routes it; the handler additionally carries @PreAuthorize("permitAll()").
        "/api/webhooks/email/");

    @Autowired
    RequestMappingHandlerMapping mapping;

    @Test
    void everyInternalHandlerDeclaresAMinimumRole() {
        List<String> violations = new ArrayList<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (isFrameworkHandler(handler)) {
                return;
            }
            Set<String> patterns = patternsOf(info);
            if (patterns.isEmpty() || allAllowListed(patterns)) {
                return;
            }
            if (!hasMethodSecurity(handler)) {
                violations.add(patterns + " -> " + handler.getMethod());
            }
        });
        assertThat(violations)
            .withFailMessage("Internal endpoints without a declared minimum role (deny-by-default): %s", violations)
            .isEmpty();
    }

    @Test
    void selfTest_undeclaredHandlerIsFlagged() throws NoSuchMethodException {
        Method unsecured = Dummy.class.getMethod("unsecured");
        HandlerMethod fake = new HandlerMethod(new Dummy(), unsecured);
        assertThat(hasMethodSecurity(fake)).isFalse(); // proves the check's failure path is real
    }

    private boolean isFrameworkHandler(HandlerMethod handler) {
        return handler.getBeanType().getName().startsWith("org.springframework");
    }

    private Set<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return info.getPatternsCondition() == null ? Set.of() : info.getPatternsCondition().getPatterns();
    }

    private boolean allAllowListed(Set<String> patterns) {
        return patterns.stream().allMatch(p -> ALLOWED_PREFIXES.stream().anyMatch(p::startsWith));
    }

    private boolean hasMethodSecurity(HandlerMethod handler) {
        Method m = handler.getMethod();
        Class<?> type = handler.getBeanType();
        return AnnotatedElementUtils.hasAnnotation(m, PreAuthorize.class)
            || AnnotatedElementUtils.hasAnnotation(m, PostAuthorize.class)
            || AnnotatedElementUtils.hasAnnotation(m, Secured.class)
            || AnnotatedElementUtils.hasAnnotation(type, PreAuthorize.class)
            || AnnotatedElementUtils.hasAnnotation(type, PostAuthorize.class)
            || AnnotatedElementUtils.hasAnnotation(type, Secured.class);
    }

    /** Self-test fixture: an internal-looking handler with NO method security. */
    static class Dummy {
        public void unsecured() {}
    }
}
