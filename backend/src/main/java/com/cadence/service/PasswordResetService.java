package com.cadence.service;

import com.cadence.api.AuthExceptions;
import com.cadence.config.AuthProperties;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Member;
import com.cadence.domain.PasswordCredential;
import com.cadence.domain.PasswordResetToken;
import com.cadence.domain.ResetStatus;
import com.cadence.integration.EmailSender;
import com.cadence.repository.PasswordResetTokenRepository;
import com.cadence.security.SecureTokens;
import com.cadence.security.TokenHasher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Forgotten-password reset (FR-020/FR-031/FR-035). Request is always enumeration-safe (a row is
 * created only for a real fallback member; the endpoint returns 202 regardless). Confirm validates
 * the token before any password policy (BE-8), consumes it atomically (single-use under
 * concurrency), rotates the credential, and revokes all of the member's sessions.
 */
@Service
public class PasswordResetService {

    private static final int MIN_PASSWORD_LEN = 8;

    private final PasswordResetTokenRepository tokens;
    private final MongoTemplate mongo;
    private final MemberService members;
    private final SessionService sessions;
    private final TokenHasher hasher;
    private final PasswordEncoder encoder;
    private final EmailSender email;
    private final AuthAuditService audit;
    private final AuthProperties props;
    private final Clock clock;

    public PasswordResetService(PasswordResetTokenRepository tokens, MongoTemplate mongo,
                                MemberService members, SessionService sessions, TokenHasher hasher,
                                PasswordEncoder encoder, EmailSender email, AuthAuditService audit,
                                AuthProperties props, Clock clock) {
        this.tokens = tokens;
        this.mongo = mongo;
        this.members = members;
        this.sessions = sessions;
        this.hasher = hasher;
        this.encoder = encoder;
        this.email = email;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    /** Always returns normally (caller responds 202) — never reveals whether the email exists. */
    public void request(String workspaceId, String emailAddress, String ip) {
        Optional<Member> member = members.findActiveByEmail(workspaceId, emailAddress);
        if (member.isPresent() && member.get().getPasswordCredential() != null) {
            Instant now = Instant.now(clock);
            String raw = SecureTokens.newToken();
            PasswordResetToken prt = new PasswordResetToken();
            prt.setMemberId(member.get().getId());
            prt.setTokenHash(hasher.hashToken(raw));
            prt.setStatus(ResetStatus.PENDING);
            prt.setCreatedAt(now);
            prt.setExpiresAt(now.plus(props.getPasswordReset().getTtl()));
            tokens.save(prt);
            String link = props.getSpaBaseUrl() + "/reset/confirm?token=" + raw;
            email.sendEmail(member.get().getId(), "password-reset", Map.of("link", link));
            audit.record(AuthEventType.PASSWORD_RESET_REQUESTED, workspaceId, member.get().getId(), "requested", ip);
        }
    }

    public void confirm(String rawToken, String newPassword, String ip) {
        // 1) Password policy FIRST. A weak password returns 400 regardless of token validity, so it
        //    leaks nothing about whether the token exists. Probing with a weak password therefore
        //    cannot be used as a token-validity oracle, and never consumes a token (SEC-6).
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LEN) {
            throw new AuthExceptions.WeakPasswordException();
        }

        String hash = hasher.hashToken(rawToken);
        Instant now = Instant.now(clock);

        // 2) Atomic single-use consume with expiry enforced IN the query (SEC-8) — exactly one
        //    concurrent confirm wins (FR-035); unknown/used/expired all yield a uniform 410.
        Query q = new Query(Criteria.where("tokenHash").is(hash)
            .and("status").is(ResetStatus.PENDING)
            .and("expiresAt").gt(now));
        Update u = new Update().set("status", ResetStatus.CONSUMED).set("consumedAt", now);
        PasswordResetToken claimed = mongo.findAndModify(
            q, u, FindAndModifyOptions.options().returnNew(false), PasswordResetToken.class);
        if (claimed == null) {
            throw new AuthExceptions.InvalidLinkException();
        }

        // 3) Rotate credential + revoke all sessions (FR-031). Guard against an erased member (SEC-7).
        Member member = members.findByIdOptional(claimed.getMemberId())
            .orElseThrow(AuthExceptions.InvalidLinkException::new);
        member.setPasswordCredential(new PasswordCredential(encoder.encode(newPassword)));
        members.save(member);
        sessions.revokeAllForMember(member.getId());
        audit.record(AuthEventType.PASSWORD_RESET_COMPLETED, member.getWorkspaceId(), member.getId(), "completed", ip);
    }
}
