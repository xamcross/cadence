package com.cadence.service;

import com.cadence.config.AuthProperties;
import com.cadence.domain.Member;
import com.cadence.domain.Session;
import com.cadence.repository.MemberRepository;
import com.cadence.repository.SessionRepository;
import com.cadence.security.JwtSupport;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, validates, renews and revokes the workspace member session (research D1).
 * A signed JWT (cheap tamper check) is paired with a MongoDB session registry so sign-out and
 * deactivation take effect on the member's next request (FR-015/FR-021/FR-028). Skew is applied
 * only to the JWT crypto check (in {@link JwtSupport}); the absolute/idle/revoked/member-status
 * checks here run at exact {@code now} (SEC-11). Renewal writes are throttled to 1/3 of the idle
 * window to avoid a per-request write (BE-3/BE-5).
 */
@Service
public class SessionService {

    private final SessionRepository sessions;
    private final MemberRepository members;
    private final JwtSupport jwt;
    private final AuthProperties props;
    private final Clock clock;

    public SessionService(SessionRepository sessions, MemberRepository members,
                          JwtSupport jwt, AuthProperties props, Clock clock) {
        this.sessions = sessions;
        this.members = members;
        this.jwt = jwt;
        this.props = props;
        this.clock = clock;
    }

    public Issued issue(Member member) {
        Instant now = Instant.now(clock);
        String jti = UUID.randomUUID().toString();
        Session s = new Session();
        s.setId(jti);
        s.setMemberId(member.getId());
        s.setWorkspaceId(member.getWorkspaceId());
        s.setRole(member.getRole());
        s.setCreatedAt(now);
        s.setLastSeenAt(now);
        s.setAbsoluteExpiresAt(now.plus(props.getSession().getAbsoluteTtl()));
        s.setIdleExpiresAt(now.plus(props.getSession().getIdleTtl()));
        s.setRevoked(false);
        sessions.save(s);
        String token = jwt.issue(jti, member.getId(), member.getWorkspaceId(), member.getRole(),
            s.getAbsoluteExpiresAt());
        return new Issued(token, s, props.getSession().getAbsoluteTtl());
    }

    public Optional<Validation> validate(String token) {
        Optional<JwtSupport.ParsedToken> parsed = jwt.verify(token);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Optional<Session> opt = sessions.findById(parsed.get().jti());
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        Session s = opt.get();
        Instant now = Instant.now(clock);
        if (s.isRevoked()
            || now.isAfter(s.getAbsoluteExpiresAt())
            || now.isAfter(s.getIdleExpiresAt())) {
            return Optional.empty();
        }
        Optional<Member> member = members.findById(s.getMemberId());
        if (member.isEmpty() || !member.get().isActive()) {
            return Optional.empty();
        }
        boolean renewed = maybeRenew(s, now);
        Duration cookieMaxAge = Duration.between(now, s.getAbsoluteExpiresAt());
        return Optional.of(new Validation(
            new Principal(s.getMemberId(), s.getWorkspaceId(), s.getRole(), s.getId()),
            renewed, cookieMaxAge));
    }

    private boolean maybeRenew(Session s, Instant now) {
        Duration idle = props.getSession().getIdleTtl();
        Instant threshold = s.getLastSeenAt().plus(idle.dividedBy(3));
        if (!now.isAfter(threshold)) {
            return false;
        }
        s.setLastSeenAt(now);
        Instant newIdle = now.plus(idle);
        if (newIdle.isAfter(s.getAbsoluteExpiresAt())) {
            newIdle = s.getAbsoluteExpiresAt();
        }
        s.setIdleExpiresAt(newIdle);
        sessions.save(s);
        return true;
    }

    /** Sign-out: revoke only the presenting session (FR-015). */
    public void revokeOne(String jti) {
        sessions.findById(jti).ifPresent(s -> {
            s.setRevoked(true);
            sessions.save(s);
        });
    }

    /** Deactivation / password reset: revoke all of a member's sessions (FR-021/FR-031). */
    public void revokeAllForMember(String memberId) {
        List<Session> all = sessions.findByMemberId(memberId);
        for (Session s : all) {
            s.setRevoked(true);
        }
        sessions.saveAll(all);
    }

    public record Issued(String jwt, Session session, Duration cookieMaxAge) {}

    public record Validation(Principal principal, boolean renewed, Duration cookieMaxAge) {}

    public record Principal(String memberId, String workspaceId,
                            com.cadence.domain.Role role, String sessionId) {}
}
