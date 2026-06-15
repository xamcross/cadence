package com.cadence.service;

import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.repository.CandidateAuditEventRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Append-only writer + reader for the candidate audit log (F04, FR-014/FR-18). The single append
 * primitive takes ENUM params only (no free String), so no caller can inject candidate-derived text;
 * combined with the non-PII document shape this makes "non-PII by construction" structural. Writes
 * via {@code MongoTemplate.insert} (the repository is a narrow append-only interface). Timestamps use
 * the injected {@link Clock} so {@code MutableClock} tests stay deterministic.
 */
@Service
public class CandidateAuditService {

    private final MongoTemplate mongoTemplate;
    private final CandidateAuditEventRepository repository;
    private final Clock clock;

    public CandidateAuditService(MongoTemplate mongoTemplate, CandidateAuditEventRepository repository, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.repository = repository;
        this.clock = clock;
    }

    /** Append one non-PII entry. Never logs candidate PII; references the candidate by internal id only. */
    public void append(String workspaceId, String candidateId, CandidateEventType eventType,
                       CandidateAuditOutcome outcome, String actorMemberId) {
        CandidateAuditEvent e = new CandidateAuditEvent();
        e.setWorkspaceId(workspaceId);
        e.setCandidateId(candidateId);
        e.setEventType(eventType);
        e.setOutcome(outcome);
        e.setActorMemberId(actorMemberId);
        e.setOccurredAt(Instant.now(clock));
        mongoTemplate.insert(e);
    }

    /** Full chronological log for a candidate (deterministic {@code (occurredAt,_id)} order). */
    public List<CandidateAuditEvent> list(String workspaceId, String candidateId) {
        return repository.findByWorkspaceIdAndCandidateIdOrderByOccurredAtAscIdAsc(workspaceId, candidateId);
    }
}
