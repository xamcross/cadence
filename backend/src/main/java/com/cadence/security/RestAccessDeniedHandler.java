package com.cadence.security;

import com.cadence.service.AuthAuditService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders the shared {error,message} envelope for an authorization failure (HTTP 403) — for an
 * authenticated-but-unauthorized member (F02 FR-014). Shape-identical across all 403s; carries NO
 * resource identifier, content, or existence signal. Wired ONLY on the @Order(3) main chain
 * (research D5) so the F00 actuator-on-public-port 404 contract and F01's /api/** 401 entry point
 * are preserved.
 *
 * Also writes a BOUNDED AUTHORIZATION_DENIED audit (FR-028/D8): role-gated denials on internal
 * endpoints are security-relevant; the per-(actor,event) throttle in AuthAuditService prevents a
 * low-privilege member from amplifying audit volume by repeated probing.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final String BODY =
        "{\"error\":\"forbidden\",\"message\":\"You do not have access to this action.\"}";

    private final AuthAuditService audit;

    public RestAccessDeniedHandler(AuthAuditService audit) {
        this.audit = audit;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        auditIfMember();
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(BODY);
    }

    private void auditIfMember() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof SessionService.Principal p) {
            // Bounded, non-PII: actor id + a coarse event code only (never the path/resource content).
            audit.authorizationDenied(p.workspaceId(), p.memberId(), "authz_denied");
        }
    }
}
