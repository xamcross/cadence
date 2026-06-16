package com.cadence.scheduler;

import com.cadence.config.EmailDeliveryProperties;
import com.cadence.domain.DispatchOutcomeReason;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.service.EmailDispatchMetrics;
import com.mongodb.client.result.UpdateResult;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Standalone stale-{@code SENDING} reaper (F22, research D5, T035). A crash between the transport accept and
 * the {@code SENT} write leaves a row stuck {@code SENDING}; this reaper CAS-marks any such row older than the
 * {@code reaperThreshold} to {@code SENT_UNCONFIRMED} and does <b>NOT</b> resend (honours FR-010 "a send the
 * provider already accepted MUST NOT be re-sent").
 *
 * <p>The transition is a CAS predicate {@code {status:SENDING, updatedAt < now-threshold}} — so it can never
 * touch a fresh/live claim. <b>Config invariant</b> (EmailDeliveryProperties): {@code reaperThreshold >
 * smtp.readTimeout + max-backoff}, so the reaper never races a still-in-flight send. Standalone (a separate
 * {@code @Scheduled} bean) so US2 closes without depending on the US4 scheduler file; the US4 sweep may
 * optionally invoke {@link #reap()}.
 */
@Component
public class EmailDispatchReaper {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchReaper.class);

    private final MongoTemplate mongo;
    private final EmailDeliveryProperties props;
    private final EmailDispatchMetrics metrics;
    private final Clock clock;

    public EmailDispatchReaper(MongoTemplate mongo, EmailDeliveryProperties props,
                               EmailDispatchMetrics metrics, Clock clock) {
        this.mongo = mongo;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cadence.email.reaper-fixed-delay:PT1M}")
    public void scheduled() {
        reap();
    }

    /** CAS every stale-SENDING row -> SENT_UNCONFIRMED (no resend). Returns the number reaped. */
    public long reap() {
        Instant threshold = Instant.now(clock).minus(props.getReaperThreshold());
        UpdateResult result = mongo.updateMulti(
            Query.query(Criteria.where("status").is(DispatchStatus.SENDING).and("updatedAt").lt(threshold)),
            new Update().set("status", DispatchStatus.SENT_UNCONFIRMED)
                .set("lastOutcomeReason", DispatchOutcomeReason.SENT_UNCONFIRMED)
                .set("updatedAt", Instant.now(clock)),
            EmailDispatch.class);
        long reaped = result.getModifiedCount();
        if (reaped > 0) {
            metrics.incReaped(reaped);
            log.warn("email dispatch reaper marked stale-SENDING rows SENT_UNCONFIRMED (no resend) {}",
                StructuredArguments.kv("reaped", reaped));
        }
        return reaped;
    }
}
