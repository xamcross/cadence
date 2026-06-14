package com.cadence.service;

import com.cadence.domain.AuthEventType;
import com.cadence.domain.Member;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Email+password fallback authentication (FR-003/FR-004/FR-005). Always performs a BCrypt
 * comparison — against a fixed dummy hash when the account is unknown/locked/SSO-only — so unknown,
 * wrong-password and locked all return the identical generic failure with uniform timing (no
 * enumeration oracle, SEC-7). On success the caller issues a session.
 */
@Service
public class AuthenticationService {

    private final MemberService members;
    private final LoginAttemptService attempts;
    private final PasswordEncoder encoder;
    private final AuthAuditService audit;
    private final String dummyHash;

    public AuthenticationService(MemberService members, LoginAttemptService attempts,
                                 PasswordEncoder encoder, AuthAuditService audit) {
        this.members = members;
        this.attempts = attempts;
        this.encoder = encoder;
        this.audit = audit;
        // A real BCrypt hash so the unknown-account path runs a genuine, equal-cost comparison.
        this.dummyHash = encoder.encode("cadence-timing-dummy-" + System.nanoTime());
    }

    /** @return the authenticated member, or empty for any failure (uniform to the caller). */
    public Optional<Member> authenticate(String workspaceId, String email, String rawPassword, String ip) {
        Optional<Member> opt = members.findByEmail(workspaceId, email);

        boolean eligible = opt.isPresent()
            && opt.get().isActive()
            && opt.get().getPasswordCredential() != null
            && !attempts.isLocked(opt.get());

        if (eligible) {
            Member member = opt.get();
            if (encoder.matches(rawPassword, member.getPasswordCredential().getBcryptHash())) {
                attempts.recordSuccess(member);
                audit.record(AuthEventType.SIGN_IN_SUCCESS, member.getWorkspaceId(), member.getId(),
                    "password", ip);
                return Optional.of(member);
            }
            attempts.recordFailure(member);
        } else {
            // Equal-cost comparison so timing does not reveal account existence/state.
            encoder.matches(rawPassword, dummyHash);
        }

        String memberId = opt.map(Member::getId).orElse(null);
        String wid = opt.map(Member::getWorkspaceId).orElse(workspaceId);
        audit.record(AuthEventType.SIGN_IN_FAILURE, wid, memberId, "password", ip);
        return Optional.empty();
    }
}
