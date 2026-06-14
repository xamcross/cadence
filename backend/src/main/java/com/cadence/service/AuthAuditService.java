package com.cadence.service;

import com.cadence.config.AuthProperties;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Role;
import com.cadence.repository.AuthAuditEventRepository;
import com.cadence.security.TokenHasher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Append-only writer for the auth audit log (FR-023). References members by internal id only and
 * stores the source IP as a keyed HMAC — never raw, never any other PII (FR-022/FR-036/SEC-6).
 */
@Service
public class AuthAuditService {

    private final AuthAuditEventRepository repository;
    private final TokenHasher tokenHasher;
    private final AuthProperties props;
    private final Clock clock;

    /**
     * Bounded in-memory throttle for AUTHORIZATION_DENIED (research D8 / FR-028): at most one audit
     * per (memberId,eventCode) per window. Keyed by the authenticated actor, so cardinality is
     * bounded by the member count; not persisted (resets on restart — an anti-amplification
     * heuristic, not a security control), mirroring the in-process LoginAttemptService limiter.
     */
    private final ConcurrentHashMap<String, Instant> lastDeniedAudit = new ConcurrentHashMap<>();

    public AuthAuditService(AuthAuditEventRepository repository, TokenHasher tokenHasher,
                            AuthProperties props, Clock clock) {
        this.repository = repository;
        this.tokenHasher = tokenHasher;
        this.props = props;
        this.clock = clock;
    }

    public void record(AuthEventType type, String workspaceId, String memberId, String outcome, String sourceIp) {
        AuthAuditEvent event = new AuthAuditEvent();
        event.setEventType(type);
        event.setWorkspaceId(workspaceId);
        event.setMemberId(memberId);
        event.setOutcome(outcome);
        event.setSourceIpHash(tokenHasher.hashIp(sourceIp));
        event.setOccurredAt(Instant.now(clock));
        repository.save(event);
    }

    /** F02: record a role change (FR-028). Non-PII ids + old/new role only. */
    public void roleChanged(String workspaceId, String actorMemberId, String targetMemberId,
                            Role oldRole, Role newRole) {
        AuthAuditEvent event = new AuthAuditEvent();
        event.setEventType(AuthEventType.ROLE_CHANGED);
        event.setWorkspaceId(workspaceId);
        event.setMemberId(actorMemberId);
        event.setTargetMemberId(targetMemberId);
        event.setOldRole(oldRole);
        event.setNewRole(newRole);
        event.setOutcome("role_changed");
        event.setOccurredAt(Instant.now(clock));
        repository.save(event);
    }

    /**
     * F02: record a security-relevant authorization refusal, bounded so probing cannot amplify
     * audit volume (FR-028). Returns true if an audit row was written, false if throttled.
     */
    public boolean authorizationDenied(String workspaceId, String actorMemberId, String eventCode) {
        Instant now = Instant.now(clock);
        String key = actorMemberId + "|" + eventCode;
        Instant last = lastDeniedAudit.get(key);
        if (last != null && !now.isAfter(last.plus(props.getRbac().getDeniedAuditWindow()))) {
            return false; // within the window — throttled, no write
        }
        lastDeniedAudit.put(key, now);
        AuthAuditEvent event = new AuthAuditEvent();
        event.setEventType(AuthEventType.AUTHORIZATION_DENIED);
        event.setWorkspaceId(workspaceId);
        event.setMemberId(actorMemberId);
        event.setOutcome(eventCode);
        event.setOccurredAt(now);
        repository.save(event);
        return true;
    }
}
