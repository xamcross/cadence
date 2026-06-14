package com.cadence.service;

import com.cadence.config.AuthProperties;
import com.cadence.domain.Member;
import com.cadence.repository.MemberRepository;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-layer abuse control with no external store (research D5):
 *  - Per-account lockout persisted on the Member (durable across restarts) — the password check is
 *    silently skipped while locked and the response stays the uniform generic 401, so lockout is
 *    not an enumeration oracle (SEC-7).
 *  - Per-source-IP throttle, in-process on the single instance (§IV/C2) — this is the user-visible
 *    429 and fires regardless of account existence (enumeration-safe, FR-032). Clock-driven with a
 *    test reset hook (QA-8). NOTE: in-process IP state resets on restart (documented MVP limit).
 */
@Service
public class LoginAttemptService {

    private final MemberRepository members;
    private final MongoTemplate mongo;
    private final AuthProperties props;
    private final Clock clock;
    private final Map<String, Deque<Instant>> ipHits = new ConcurrentHashMap<>();

    public LoginAttemptService(MemberRepository members, MongoTemplate mongo, AuthProperties props, Clock clock) {
        this.members = members;
        this.mongo = mongo;
        this.props = props;
        this.clock = clock;
    }

    // ---- per-IP throttle ----

    /** Returns true if the IP is allowed; records the attempt. Returns false when over the limit. */
    public synchronized boolean tryConsumeIp(String ip) {
        if (ip == null) {
            return true;
        }
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(props.getLockout().getWindow());
        Deque<Instant> hits = ipHits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        while (!hits.isEmpty() && hits.peekFirst().isBefore(cutoff)) {
            hits.pollFirst();
        }
        if (hits.size() >= props.getLockout().getMaxAttempts()) {
            return false;
        }
        hits.addLast(now);
        return true;
    }

    /** Test hook — clear in-process IP counters. */
    public void resetIpCounters() {
        ipHits.clear();
    }

    // ---- per-account lockout ----

    public boolean isLocked(Member member) {
        Instant until = member.getLockedUntil();
        return until != null && Instant.now(clock).isBefore(until);
    }

    public void recordFailure(Member member) {
        // Atomic $inc so concurrent failures cannot lose increments (SEC-10).
        Query q = new Query(Criteria.where("_id").is(member.getId()));
        Member updated = mongo.findAndModify(
            q, new Update().inc("failedLoginCount", 1),
            FindAndModifyOptions.options().returnNew(true), Member.class);
        if (updated != null && updated.getFailedLoginCount() >= props.getLockout().getMaxAttempts()) {
            mongo.updateFirst(q,
                new Update().set("lockedUntil", Instant.now(clock).plus(props.getLockout().getWindow())),
                Member.class);
        }
    }

    public void recordSuccess(Member member) {
        if (member.getFailedLoginCount() != 0 || member.getLockedUntil() != null) {
            member.setFailedLoginCount(0);
            member.setLockedUntil(null);
            members.save(member);
        }
    }
}
