package com.cadence.service;

import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Operational metrics for the F22 dispatch channel (FR-024 / research D11), published on the existing
 * actuator {@code metrics} endpoint (no new infra). Counters track each terminal transition and the two
 * recovery seams; a gauge exposes the {@code PENDING} backlog depth (a live repository count) so an
 * operator can alarm on a stuck outbox.
 *
 * <p>PII discipline: every meter is value-free — counts only, no recipient/subject/body, no tags carrying
 * candidate-resolvable data. The increments are wired at the existing CAS transition points (minimal touch);
 * a metric failure must never break a send, so callers fire-and-forget.
 */
@Component
public class EmailDispatchMetrics {

    private final Counter sent;
    private final Counter refused;
    private final Counter failed;
    private final Counter bounced;
    private final Counter reaped;          // stale-SENDING -> SENT_UNCONFIRMED (no resend)
    private final Counter webhookUnmatched; // webhook event with no correlating dispatch row
    private final Counter reconciliationConflict; // SENDING->SENT CAS lost (row no longer SENDING mid-flight)

    public EmailDispatchMetrics(MeterRegistry registry, MongoTemplate mongo) {
        this.sent = Counter.builder("cadence.email.dispatch.sent")
            .description("Candidate emails accepted by the transport").register(registry);
        this.refused = Counter.builder("cadence.email.dispatch.refused")
            .description("Candidate emails refused by the consent gate").register(registry);
        this.failed = Counter.builder("cadence.email.dispatch.failed")
            .description("Candidate email dispatches that terminally failed").register(registry);
        this.bounced = Counter.builder("cadence.email.dispatch.bounced")
            .description("Candidate emails reported as a hard bounce / complaint").register(registry);
        this.reaped = Counter.builder("cadence.email.dispatch.reaped")
            .description("Stale-SENDING rows reaped to SENT_UNCONFIRMED (no resend)").register(registry);
        this.webhookUnmatched = Counter.builder("cadence.email.dispatch.webhook.unmatched")
            .description("Inbound webhook events with no correlating dispatch row").register(registry);
        this.reconciliationConflict = Counter.builder("cadence.email.dispatch.reconciliation_conflict")
            .description("SENDING->SENT CAS found the row no longer SENDING (no clean SENT reported)").register(registry);

        // PENDING backlog depth — a live count, so a stuck/growing outbox is observable (D11). The gauge
        // closes over the MongoTemplate; Micrometer polls the supplier on scrape (never on the hot path).
        registry.gauge("cadence.email.dispatch.pending", mongo,
            t -> t.count(Query.query(Criteria.where("status").is(DispatchStatus.PENDING)), EmailDispatch.class));
    }

    public void incSent() { sent.increment(); }

    public void incRefused() { refused.increment(); }

    public void incFailed() { failed.increment(); }

    public void incBounced() { bounced.increment(); }

    public void incReaped(long n) { if (n > 0) reaped.increment(n); }

    public void incWebhookUnmatched() { webhookUnmatched.increment(); }

    public void incReconciliationConflict() { reconciliationConflict.increment(); }
}
