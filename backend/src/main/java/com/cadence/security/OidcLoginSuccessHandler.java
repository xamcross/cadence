package com.cadence.security;

import com.cadence.config.AuthProperties;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Member;
import com.cadence.service.AuthAuditService;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * On successful OIDC sign-in: map (provider, subject) → ACTIVE member, invalidate the IdP login
 * HttpSession to defeat session fixation (SEC-4), issue the cad_session cookie, audit, and redirect
 * to the SPA. No active member → /login?error=no_access with no cookie (FR-007).
 */
@Component
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final MemberService members;
    private final SessionService sessions;
    private final SessionCookieFactory cookies;
    private final AuthAuditService audit;
    private final AuthProperties props;

    public OidcLoginSuccessHandler(MemberService members, SessionService sessions,
                                   SessionCookieFactory cookies, AuthAuditService audit,
                                   AuthProperties props) {
        this.members = members;
        this.sessions = sessions;
        this.cookies = cookies;
        this.audit = audit;
        this.props = props;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        // Session fixation: drop the servlet session created during the OIDC handshake (SEC-4).
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }

        String provider = (authentication instanceof OAuth2AuthenticationToken t)
            ? t.getAuthorizedClientRegistrationId() : "cadence-oidc";
        String subject = (authentication.getPrincipal() instanceof OidcUser u) ? u.getSubject() : null;

        Optional<Member> member = (subject == null)
            ? Optional.empty() : members.findActiveBySso(provider, subject);

        if (member.isEmpty()) {
            audit.record(AuthEventType.SIGN_IN_FAILURE, null, null, "sso_no_member", request.getRemoteAddr());
            response.sendRedirect(props.getSpaBaseUrl() + "/login?error=no_access");
            return;
        }

        Member m = member.get();
        SessionService.Issued issued = sessions.issue(m);
        response.addHeader(HttpHeaders.SET_COOKIE,
            cookies.build(issued.jwt(), issued.cookieMaxAge()).toString());
        audit.record(AuthEventType.SIGN_IN_SUCCESS, m.getWorkspaceId(), m.getId(), "sso", request.getRemoteAddr());
        response.sendRedirect(props.getSpaBaseUrl() + "/");
    }
}
