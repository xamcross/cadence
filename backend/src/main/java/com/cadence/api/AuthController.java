package com.cadence.api;

import com.cadence.domain.Member;
import com.cadence.service.AuthenticationService;
import com.cadence.service.LoginAttemptService;
import com.cadence.service.MemberService;
import com.cadence.service.PasswordResetService;
import com.cadence.service.SessionService;
import com.cadence.service.WorkspaceConfigService;
import com.cadence.security.SessionCookieFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Auth endpoints (contracts/auth-api.md). Public (no session): login + password reset under
 * /api/public/auth/**. Session-required: /me + logout under /api/internal/auth/**.
 */
@RestController
public class AuthController {

    private final AuthenticationService authentication;
    private final PasswordResetService passwordReset;
    private final LoginAttemptService attempts;
    private final SessionService sessions;
    private final SessionCookieFactory cookies;
    private final MemberService members;
    private final WorkspaceConfigService workspaceConfig;

    public AuthController(AuthenticationService authentication, PasswordResetService passwordReset,
                          LoginAttemptService attempts, SessionService sessions,
                          SessionCookieFactory cookies, MemberService members,
                          WorkspaceConfigService workspaceConfig) {
        this.authentication = authentication;
        this.passwordReset = passwordReset;
        this.attempts = attempts;
        this.sessions = sessions;
        this.cookies = cookies;
        this.members = members;
        this.workspaceConfig = workspaceConfig;
    }

    @GetMapping("/api/internal/auth/me")
    @PreAuthorize("isAuthenticated()") // authenticated-any-role (F02 deny-by-default inventory, D2)
    public ResponseEntity<AuthDtos.MemberSummary> me(@AuthenticationPrincipal SessionService.Principal principal) {
        Member m = members.findById(principal.memberId());
        return ResponseEntity.ok(new AuthDtos.MemberSummary(
            m.getId(), m.getWorkspaceId(), m.getRole(), m.getDisplayName(), m.getEmail(),
            workspaceConfig.isConfigured(m.getWorkspaceId())));
    }

    @PostMapping("/api/public/auth/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthDtos.LoginRequest req, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        if (!attempts.tryConsumeIp(ip)) {
            throw new AuthExceptions.RateLimitedException();
        }
        Optional<Member> member = authentication.authenticate(req.workspaceId(), req.email(), req.password(), ip);
        if (member.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid_credentials", "message", "Invalid email or password."));
        }
        SessionService.Issued issued = sessions.issue(member.get());
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookies.build(issued.jwt(), issued.cookieMaxAge()).toString())
            .body(new AuthDtos.MemberSummary(member.get().getId(), member.get().getWorkspaceId(),
                member.get().getRole(), member.get().getDisplayName(), member.get().getEmail(),
                workspaceConfig.isConfigured(member.get().getWorkspaceId())));
    }

    @PostMapping("/api/public/auth/password-reset/request")
    public ResponseEntity<Void> resetRequest(@Valid @RequestBody AuthDtos.ResetRequestRequest req,
                                             HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        if (!attempts.tryConsumeIp(ip)) {
            throw new AuthExceptions.RateLimitedException();
        }
        passwordReset.request(req.workspaceId(), req.email(), ip);
        return ResponseEntity.accepted().build(); // 202 regardless (enumeration-safe)
    }

    @PostMapping("/api/public/auth/password-reset/confirm")
    public ResponseEntity<Void> resetConfirm(@Valid @RequestBody AuthDtos.ResetConfirmRequest req,
                                             HttpServletRequest http) {
        passwordReset.confirm(req.token(), req.newPassword(), http.getRemoteAddr());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/internal/auth/logout")
    @PreAuthorize("isAuthenticated()") // authenticated-any-role (F02 deny-by-default inventory, D2)
    public ResponseEntity<Void> logout(@AuthenticationPrincipal SessionService.Principal principal) {
        sessions.revokeOne(principal.sessionId());
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
            .build();
    }
}
