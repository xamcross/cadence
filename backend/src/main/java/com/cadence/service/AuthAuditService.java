package com.cadence.service;

import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.repository.AuthAuditEventRepository;
import com.cadence.security.TokenHasher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Append-only writer for the auth audit log (FR-023). References members by internal id only and
 * stores the source IP as a keyed HMAC — never raw, never any other PII (FR-022/FR-036/SEC-6).
 */
@Service
public class AuthAuditService {

    private final AuthAuditEventRepository repository;
    private final TokenHasher tokenHasher;
    private final Clock clock;

    public AuthAuditService(AuthAuditEventRepository repository, TokenHasher tokenHasher, Clock clock) {
        this.repository = repository;
        this.tokenHasher = tokenHasher;
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
}
