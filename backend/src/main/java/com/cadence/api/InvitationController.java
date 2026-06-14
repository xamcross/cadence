package com.cadence.api;

import com.cadence.domain.Member;
import com.cadence.service.InvitationService;
import com.cadence.service.LoginAttemptService;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import com.cadence.security.SessionCookieFactory;
import com.cadence.domain.Invitation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Invitations (contracts/auth-api.md). Admin create under /api/internal; public validate/accept
 * under /api/public/auth. Coarse ADMIN check here; the full RBAC matrix is F02.
 */
@RestController
public class InvitationController {

    private final InvitationService invitations;
    private final MemberService members;
    private final SessionService sessions;
    private final SessionCookieFactory cookies;
    private final LoginAttemptService attempts;

    public InvitationController(InvitationService invitations, MemberService members,
                               SessionService sessions, SessionCookieFactory cookies,
                               LoginAttemptService attempts) {
        this.invitations = invitations;
        this.members = members;
        this.sessions = sessions;
        this.cookies = cookies;
        this.attempts = attempts;
    }

    @PostMapping("/api/internal/invitations")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal SessionService.Principal principal,
            @Valid @RequestBody AuthDtos.InviteCreateRequest req,
            HttpServletRequest http) {
        Invitation inv = invitations.create(
            principal.workspaceId(), principal.memberId(), req.email(), req.role(), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("invitationId", inv.getId(), "expiresAt", inv.getExpiresAt().toString()));
    }

    @GetMapping("/api/public/auth/invitations/{token}")
    public ResponseEntity<AuthDtos.InvitationView> validate(@PathVariable String token, HttpServletRequest http) {
        if (!attempts.tryConsumeIp(http.getRemoteAddr())) {
            throw new AuthExceptions.RateLimitedException();
        }
        InvitationService.InvitationView v = invitations.validate(token);
        return ResponseEntity.ok(new AuthDtos.InvitationView(v.email(), v.role(), v.needsPassword()));
    }

    @PostMapping("/api/public/auth/invitations/{token}/accept")
    public ResponseEntity<AuthDtos.MemberSummary> accept(
            @PathVariable String token,
            @RequestBody AuthDtos.InviteAcceptRequest req,
            HttpServletRequest http) {
        if (!attempts.tryConsumeIp(http.getRemoteAddr())) {
            throw new AuthExceptions.RateLimitedException();
        }
        Member member = invitations.accept(token, req.password(), http.getRemoteAddr());
        SessionService.Issued issued = sessions.issue(member);
        return ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.SET_COOKIE, cookies.build(issued.jwt(), issued.cookieMaxAge()).toString())
            .body(new AuthDtos.MemberSummary(member.getId(), member.getWorkspaceId(), member.getRole(),
                member.getDisplayName(), member.getEmail()));
    }
}
