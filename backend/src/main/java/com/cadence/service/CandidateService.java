package com.cadence.service;

import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.ErasureState;
import com.cadence.domain.LawfulBasis;
import com.cadence.repository.CandidateRepository;
import com.cadence.security.PiiCrypto;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The canonical candidate-record creation seam (F04, FR-005) plus lawful-basis record/withdraw (US1).
 * F04 exposes NO HTTP candidate-create endpoint — creation surfaces (F13/F40/F42) MUST create records
 * through {@link #create} so the GDPR defaults and the creation audit are centralized. Basis writes
 * use a targeted single-document {@code $set} (no whole-document read-modify-write, F02/F03 lesson).
 */
@Service
public class CandidateService {

    private final MongoTemplate mongoTemplate;
    private final CandidateRepository candidates;
    private final PiiCrypto crypto;
    private final Clock clock;
    private final CandidateAuditService audit;

    public CandidateService(MongoTemplate mongoTemplate, CandidateRepository candidates,
                            PiiCrypto crypto, Clock clock, CandidateAuditService audit) {
        this.mongoTemplate = mongoTemplate;
        this.candidates = candidates;
        this.crypto = crypto;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * Create a candidate with the GDPR defaults (not erased, not withdrawn, not flagged; basis absent
     * unless supplied -> the gate stays deny:no_basis until one is recorded). Appends RECORD_CREATED.
     */
    public Candidate create(String workspaceId, String name, String email, String phone,
                            Optional<LawfulBasis> initialBasis, String actorMemberId) {
        Instant now = Instant.now(clock);
        Candidate c = new Candidate();
        c.setWorkspaceId(workspaceId);
        c.setName(name);
        c.setEmail(email);
        c.setPhone(phone);
        c.setEmailHash(crypto.emailHash(email));
        c.setErasureState(ErasureState.ACTIVE);
        c.setBasisWithdrawn(false);
        c.setRetentionFlagged(false);
        c.setLastContactAt(now);
        c.setCreatedAt(now);
        initialBasis.ifPresent(b -> {
            c.setLawfulBasis(b);
            c.setBasisRecordedAt(now);
            c.setBasisActorMemberId(actorMemberId);
        });
        Candidate saved = candidates.save(c);
        audit.append(workspaceId, saved.getId(), CandidateEventType.RECORD_CREATED,
            CandidateAuditOutcome.CREATED, actorMemberId);
        return saved;
    }

    /** US1: record (or re-record) the email lawful basis on an ACTIVE candidate. Targeted $set; audited only when matched. */
    public void recordBasis(String workspaceId, String candidateId, LawfulBasis basis, String actorMemberId) {
        Query q = Query.query(Criteria.where("_id").is(candidateId).and("workspaceId").is(workspaceId)
            .and("erasureState").is(ErasureState.ACTIVE));
        Update u = new Update()
            .set("lawfulBasis", basis)
            .set("basisRecordedAt", Instant.now(clock))
            .set("basisActorMemberId", actorMemberId)
            .set("basisWithdrawn", false)
            .set("basisWithdrawnAt", null);
        UpdateResult r = mongoTemplate.updateFirst(q, u, Candidate.class);
        if (r.getMatchedCount() == 1) {
            audit.append(workspaceId, candidateId, CandidateEventType.BASIS_RECORDED,
                CandidateAuditOutcome.RECORDED, actorMemberId);
        }
    }

    /** US1: withdraw the basis (opt-out, GDPR Art. 7(3)) on an ACTIVE candidate. Targeted $set; audited only when matched. */
    public void withdrawBasis(String workspaceId, String candidateId, String actorMemberId) {
        Query q = Query.query(Criteria.where("_id").is(candidateId).and("workspaceId").is(workspaceId)
            .and("erasureState").is(ErasureState.ACTIVE));
        Update u = new Update()
            .set("basisWithdrawn", true)
            .set("basisWithdrawnAt", Instant.now(clock));
        UpdateResult r = mongoTemplate.updateFirst(q, u, Candidate.class);
        if (r.getMatchedCount() == 1) {
            audit.append(workspaceId, candidateId, CandidateEventType.BASIS_WITHDRAWN,
                CandidateAuditOutcome.WITHDRAWN, actorMemberId);
        }
    }
}
