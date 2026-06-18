package com.cadence.service;

import com.cadence.domain.AtsConnection;
import com.cadence.domain.AtsConnectionStatus;
import com.cadence.domain.AtsSyncRun;
import com.cadence.domain.Candidate;
import com.cadence.domain.ErasureState;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.integration.AtsApiException;
import com.cadence.integration.AtsCandidateRecord;
import com.cadence.integration.AtsConnector;
import com.cadence.integration.AtsFetchResult;
import com.cadence.integration.AtsProvider;
import com.cadence.repository.AtsConnectionRepository;
import com.cadence.repository.AtsSyncRunRepository;
import com.cadence.repository.CandidateRepository;
import com.cadence.scheduler.DeadLetterService;
import com.cadence.security.PiiCrypto;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inbound sync (F40, US2): pull candidates from the authenticated Greenhouse API and reconcile them into
 * {@code candidates}, then record an {@link AtsSyncRun}. Reconcile is an explicit RESOLVE-then-guarded-WRITE
 * (data-model section 4) — resolve by the authoritative external ref (no erasure filter), else adopt a native
 * candidate by email-hash ONLY when it has no external ref, else insert. Every write is guarded on
 * {@code erasureState == ACTIVE}, so an erased record no-ops (no PII re-write) and is never re-created
 * (the resurrection defense; the external ref is retained on erasure so resolve always finds it).
 *
 * <p>PII discipline: logs carry counts + ids only — never a name/email/stage label. The decrypted credential
 * comes from the repo-loaded connection (the converter decrypts on read) and is passed transiently; never logged.
 */
@Service
public class AtsSyncService {

    private static final Logger log = LoggerFactory.getLogger(AtsSyncService.class);

    private final AtsConnectionRepository connections;
    private final CandidateRepository candidates;
    private final AtsSyncRunRepository syncRuns;
    private final MongoTemplate mongo;
    private final PiiCrypto crypto;
    private final DeadLetterService deadLetters;
    private final RecruiterNotificationService notifications;
    private final Map<AtsProvider, AtsConnector> connectors = new EnumMap<>(AtsProvider.class);
    private final Clock clock;

    public AtsSyncService(AtsConnectionRepository connections, CandidateRepository candidates,
                          AtsSyncRunRepository syncRuns, MongoTemplate mongo, PiiCrypto crypto,
                          DeadLetterService deadLetters, RecruiterNotificationService notifications,
                          List<AtsConnector> connectorList, Clock clock) {
        this.connections = connections;
        this.candidates = candidates;
        this.syncRuns = syncRuns;
        this.mongo = mongo;
        this.crypto = crypto;
        this.deadLetters = deadLetters;
        this.notifications = notifications;
        for (AtsConnector c : connectorList) {
            connectors.put(c.provider(), c);
        }
        this.clock = clock;
    }

    /** One sync pass for a connected workspace. Records an {@link AtsSyncRun}; flips the connection on failure. */
    public void syncWorkspace(AtsConnection conn) {
        Instant started = Instant.now(clock);
        String workspaceId = conn.getWorkspaceId();
        AtsProvider provider = conn.getProvider();
        AtsConnector connector = connectors.get(provider);
        if (connector == null || conn.getApiKey() == null) {
            return; // not deliverable; nothing to do
        }
        int processed = 0;
        int created = 0;
        int updated = 0;
        int skipped = 0;
        try {
            AtsFetchResult result = connector.fetchCandidates(workspaceId, conn.getApiKey(), conn.getSyncCursor());
            for (AtsCandidateRecord rec : result.records()) {
                processed++;
                Outcome o = reconcile(workspaceId, provider, rec, Instant.now(clock));
                switch (o) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                }
            }
            Instant finished = Instant.now(clock);
            mongo.updateFirst(Query.query(Criteria.where("workspaceId").is(workspaceId).and("provider").is(provider)),
                new Update().set("status", AtsConnectionStatus.CONNECTED).set("lastSyncAt", finished)
                    .set("lastErrorCategory", null).set("syncCursor", result.nextCursor()).set("updatedAt", finished),
                AtsConnection.class);
            recordRun(workspaceId, provider, started, finished, "SUCCESS", processed, created, updated, skipped, null);
            if (processed > 0) {
                log.info("ATS sync ok {} {} {} {} {}",
                    StructuredArguments.kv("workspaceId", workspaceId),
                    StructuredArguments.kv("processed", processed),
                    StructuredArguments.kv("created", created),
                    StructuredArguments.kv("updated", updated),
                    StructuredArguments.kv("skipped", skipped));
            }
        } catch (AtsApiException e) {
            Instant finished = Instant.now(clock);
            AtsConnectionStatus newStatus = e.isNeedsReauth()
                ? AtsConnectionStatus.NEEDS_REAUTH : AtsConnectionStatus.ERROR;
            mongo.updateFirst(Query.query(Criteria.where("workspaceId").is(workspaceId).and("provider").is(provider)),
                new Update().set("status", newStatus).set("lastErrorCategory", e.getCategory()).set("updatedAt", finished),
                AtsConnection.class);
            recordRun(workspaceId, provider, started, finished, "FAILED", processed, created, updated, skipped, e.getCategory());
            deadLetters.recordFailure("ats-sync", new IllegalStateException("ats_sync_failed: " + e.getCategory()), null);
            notifications.notify(workspaceId, null, RecruiterNotificationType.ATS_SYNC_FAILED);
            log.warn("ATS sync failed {} {} {}",
                StructuredArguments.kv("workspaceId", workspaceId),
                StructuredArguments.kv("category", e.getCategory()),
                StructuredArguments.kv("needsReauth", e.isNeedsReauth()));
        }
    }

    private enum Outcome { CREATED, UPDATED, SKIPPED }

    /** Resolve-then-guarded-write (the resurrection defense — NEVER a single upsert with erasureState in the filter). */
    private Outcome reconcile(String workspaceId, AtsProvider provider, AtsCandidateRecord rec, Instant now) {
        // (1) Resolve by the authoritative external ref (NO erasure filter), scoped to THIS provider.
        Optional<Candidate> existing = candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(
            workspaceId, provider, rec.externalRef());
        // (2) Else adopt a native candidate (no external ref) by email hash.
        if (existing.isEmpty() && rec.email() != null && !rec.email().isBlank()) {
            String emailHash = crypto.emailHash(rec.email());
            existing = candidates.findByWorkspaceIdAndEmailHash(workspaceId, emailHash).stream()
                .filter(c -> c.getAtsExternalRef() == null && c.getErasureState() == ErasureState.ACTIVE)
                .findFirst();
        }
        if (existing.isPresent()) {
            // Guarded update — an ERASED row no-ops (matchedCount 0 -> SKIPPED), so no PII is re-written.
            long matched = mongo.updateFirst(
                Query.query(Criteria.where("_id").is(existing.get().getId())
                    .and("erasureState").is(ErasureState.ACTIVE)),
                new Update()
                    .set("atsProvider", provider)
                    .set("atsExternalRef", rec.externalRef())
                    .set("atsExternalJobId", rec.externalJobId())
                    .set("atsExternalJobTitle", rec.externalJobTitle())
                    .set("atsStageLabel", rec.stageLabel())
                    .set("atsSyncedAt", now),
                Candidate.class).getMatchedCount();
            return matched == 1 ? Outcome.UPDATED : Outcome.SKIPPED;
        }
        // (3) Genuinely absent -> insert a new ACTIVE candidate (idempotent on the partial-unique index).
        Candidate c = new Candidate();
        c.setWorkspaceId(workspaceId);
        c.setName(rec.name());
        c.setEmail(rec.email());
        c.setPhone(rec.phone());
        c.setEmailHash(rec.email() == null || rec.email().isBlank() ? null : crypto.emailHash(rec.email()));
        c.setErasureState(ErasureState.ACTIVE);
        c.setAtsProvider(provider);
        c.setAtsExternalRef(rec.externalRef());
        c.setAtsExternalJobId(rec.externalJobId());
        c.setAtsExternalJobTitle(rec.externalJobTitle());
        c.setAtsStageLabel(rec.stageLabel());
        c.setAtsSyncedAt(now);
        c.setCreatedAt(now);
        c.setLastContactAt(now);
        try {
            candidates.insert(c);
            return Outcome.CREATED;
        } catch (DuplicateKeyException dup) {
            // A concurrent sweep inserted the same (workspace,provider,ref) — re-resolve and guarded-update.
            Optional<Candidate> raced = candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(
                workspaceId, provider, rec.externalRef());
            if (raced.isPresent()) {
                long matched = mongo.updateFirst(
                    Query.query(Criteria.where("_id").is(raced.get().getId()).and("erasureState").is(ErasureState.ACTIVE)),
                    new Update().set("atsExternalJobId", rec.externalJobId())
                        .set("atsExternalJobTitle", rec.externalJobTitle())
                        .set("atsStageLabel", rec.stageLabel()).set("atsSyncedAt", now),
                    Candidate.class).getMatchedCount();
                return matched == 1 ? Outcome.UPDATED : Outcome.SKIPPED;
            }
            return Outcome.SKIPPED;
        }
    }

    private void recordRun(String workspaceId, AtsProvider provider, Instant started, Instant finished, String outcome,
                           int processed, int created, int updated, int skipped, String errorCategory) {
        AtsSyncRun run = new AtsSyncRun();
        run.setWorkspaceId(workspaceId);
        run.setProvider(provider);
        run.setStartedAt(started);
        run.setFinishedAt(finished);
        run.setOutcome(outcome);
        run.setProcessed(processed);
        run.setCreated(created);
        run.setUpdated(updated);
        run.setSkipped(skipped);
        run.setErrorCategory(errorCategory);
        syncRuns.save(run);
    }
}
