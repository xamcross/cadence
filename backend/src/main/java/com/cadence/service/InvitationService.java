package com.cadence.service;

import com.cadence.api.AuthExceptions;
import com.cadence.config.AuthProperties;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Invitation;
import com.cadence.domain.InvitationStatus;
import com.cadence.domain.Member;
import com.cadence.domain.PasswordCredential;
import com.cadence.domain.Role;
import com.cadence.integration.EmailSender;
import com.cadence.repository.InvitationRepository;
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
 * Invite-only provisioning (FR-016..019/FR-033/FR-035). Create refuses an existing active member
 * (no takeover, FR-033). Accept validates the token before password policy (BE-8) and consumes it
 * atomically (single-use under concurrency, FR-035), then creates the member.
 */
@Service
public class InvitationService {

    private static final int MIN_PASSWORD_LEN = 8;

    private final InvitationRepository invitations;
    private final MongoTemplate mongo;
    private final MemberService members;
    private final TokenHasher hasher;
    private final PasswordEncoder encoder;
    private final EmailSender email;
    private final AuthAuditService audit;
    private final AuthProperties props;
    private final Clock clock;

    public InvitationService(InvitationRepository invitations, MongoTemplate mongo,
                             MemberService members, TokenHasher hasher, PasswordEncoder encoder,
                             EmailSender email, AuthAuditService audit, AuthProperties props, Clock clock) {
        this.invitations = invitations;
        this.mongo = mongo;
        this.members = members;
        this.hasher = hasher;
        this.encoder = encoder;
        this.email = email;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public Invitation create(String workspaceId, String invitedByMemberId, String emailAddress,
                             Role role, String ip) {
        if (members.findActiveByEmail(workspaceId, emailAddress).isPresent()) {
            throw new AuthExceptions.AlreadyMemberException();
        }
        Instant now = Instant.now(clock);
        String raw = SecureTokens.newToken();
        Invitation inv = new Invitation();
        inv.setWorkspaceId(workspaceId);
        inv.setEmail(emailAddress);
        inv.setRole(role);
        inv.setTokenHash(hasher.hashToken(raw));
        inv.setStatus(InvitationStatus.PENDING);
        inv.setInvitedByMemberId(invitedByMemberId);
        inv.setCreatedAt(now);
        inv.setExpiresAt(now.plus(props.getInvitation().getTtl()));
        Invitation saved = invitations.save(inv);
        String link = props.getSpaBaseUrl() + "/accept-invite?token=" + raw;
        email.sendEmail(saved.getId(), "invitation", Map.of("link", link));
        audit.record(AuthEventType.INVITATION_ISSUED, workspaceId, invitedByMemberId, "issued", ip);
        return saved;
    }

    public InvitationView validate(String rawToken) {
        Invitation inv = pendingOrThrow(rawToken);
        return new InvitationView(inv.getEmail(), inv.getRole(), true);
    }

    public Member accept(String rawToken, String password, String ip) {
        // 1) password policy FIRST — a weak password returns 400 regardless of token validity, so it
        //    is not a token-validity oracle and never consumes a token (SEC-6).
        if (password == null || password.length() < MIN_PASSWORD_LEN) {
            throw new AuthExceptions.WeakPasswordException();
        }
        // 2) atomic single-use consume with expiry enforced IN the query (FR-035/SEC-8)
        Instant now = Instant.now(clock);
        Query q = new Query(Criteria.where("tokenHash").is(hasher.hashToken(rawToken))
            .and("status").is(InvitationStatus.PENDING)
            .and("expiresAt").gt(now));
        Update u = new Update().set("status", InvitationStatus.CONSUMED).set("consumedAt", now);
        Invitation claimed = mongo.findAndModify(
            q, u, FindAndModifyOptions.options().returnNew(false), Invitation.class);
        if (claimed == null) {
            throw new AuthExceptions.InvalidLinkException();
        }
        // 4) create the member (guard against a race where the email became a member meanwhile)
        if (members.findActiveByEmail(claimed.getWorkspaceId(), claimed.getEmail()).isPresent()) {
            throw new AuthExceptions.AlreadyMemberException();
        }
        Member member = members.create(claimed.getWorkspaceId(), claimed.getEmail(),
            claimed.getEmail(), claimed.getRole(),
            new PasswordCredential(encoder.encode(password)), null);
        audit.record(AuthEventType.INVITATION_CONSUMED, member.getWorkspaceId(), member.getId(), "accepted", ip);
        return member;
    }

    private Invitation pendingOrThrow(String rawToken) {
        String hash = hasher.hashToken(rawToken);
        Optional<Invitation> inv = invitations.findByTokenHash(hash);
        if (inv.isEmpty()
            || inv.get().getStatus() != InvitationStatus.PENDING
            || inv.get().getExpiresAt().isBefore(Instant.now(clock))) {
            throw new AuthExceptions.InvalidLinkException();
        }
        return inv.get();
    }

    public record InvitationView(String email, Role role, boolean needsPassword) {}
}
