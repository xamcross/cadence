package com.cadence.emaildelivery;

import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Candidate;
import com.cadence.domain.DispatchOutcomeReason;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.RecruiterNotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T036 (US3) — the inbound bounce/delivery webhook (contract B). A bad signature -> 401 with NO state change;
 * a hard bounce -> row BOUNCED + candidate undeliverable + EMAIL_DISPATCH_BOUNCED audit + recruiter
 * notification; a soft bounce -> no candidate flag (row reason only); a duplicate eventId -> single flag/notify
 * (idempotent); an unknown providerMessageRef -> 200 ack with no state change; a cross-workspace event ->
 * no state change. The webhook secret matches application-test.yml.
 */
class EmailBounceWebhookTest extends EmailDeliveryItBase {

    private static final String SECRET = "test-webhook-secret-f22";
    private static final String URL = "/api/webhooks/email/events";

    /** Seed a SENT dispatch row with a providerMessageRef for a (contactable) candidate in the given workspace. */
    private EmailDispatch seedSent(String workspaceId, String candidateId, String providerRef) {
        EmailDispatch d = new EmailDispatch();
        d.setWorkspaceId(workspaceId);
        d.setCandidateId(candidateId);
        d.setMessageType(EmailMessageType.CONFIRMATION);
        d.setStageKey("BASE");
        d.setIdempotencyKey("idem-" + providerRef);
        d.setStatus(DispatchStatus.SENT);
        d.setProviderMessageRef(providerRef);
        d.setScheduledFor(Instant.now(clock));
        d.setNextAttemptAt(Instant.now(clock));
        d.setSentAt(Instant.now(clock));
        d.setCreatedAt(Instant.now(clock));
        d.setUpdatedAt(Instant.now(clock));
        return mongoTemplate.save(d);
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String event(String eventId, String ref, String type) {
        return "{\"eventId\":\"" + eventId + "\",\"providerMessageRef\":\"" + ref
            + "\",\"type\":\"" + type + "\",\"occurredAt\":\"2026-06-16T00:00:00Z\"}";
    }

    private void postSigned(String body) throws Exception {
        mvc.perform(post(URL).contentType(APPLICATION_JSON).header("X-Cadence-Signature", sign(body)).content(body))
            .andExpect(status().isOk());
    }

    @Test
    void badSignature_returns401_noStateChange() throws Exception {
        seedContactableCandidate("c1", "Dana", "dana@example.com");
        seedSent(WS, "c1", "ref-1");
        String body = event("evt-1", "ref-1", "bounce");

        mvc.perform(post(URL).contentType(APPLICATION_JSON).header("X-Cadence-Signature", "deadbeef").content(body))
            .andExpect(status().isUnauthorized());

        EmailDispatch row = mongoTemplate.findOne(
            Query.query(Criteria.where("providerMessageRef").is("ref-1")), EmailDispatch.class);
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.SENT); // unchanged
        Candidate c = mongoTemplate.findById("c1", Candidate.class);
        assertThat(c.isUndeliverable()).isFalse();
        assertThat(mongoTemplate.findAll(RecruiterNotification.class)).isEmpty();
    }

    @Test
    void hardBounce_flagsCandidate_audits_notifies() throws Exception {
        seedContactableCandidate("c1", "Dana", "dana@example.com");
        seedSent(WS, "c1", "ref-1");

        postSigned(event("evt-1", "ref-1", "bounce"));

        EmailDispatch row = mongoTemplate.findOne(
            Query.query(Criteria.where("providerMessageRef").is("ref-1")), EmailDispatch.class);
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.BOUNCED);
        assertThat(row.getLastOutcomeReason()).isEqualTo(DispatchOutcomeReason.HARD_BOUNCE);

        Candidate c = mongoTemplate.findById("c1", Candidate.class);
        assertThat(c.isUndeliverable()).isTrue();
        assertThat(c.getUndeliverableReason()).isEqualTo(DispatchOutcomeReason.HARD_BOUNCE);
        assertThat(c.getUndeliverableAt()).isNotNull();

        List<AuthAuditEvent> audits = mongoTemplate.find(
            Query.query(Criteria.where("eventType").is(AuthEventType.EMAIL_DISPATCH_BOUNCED)), AuthAuditEvent.class);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getOutcome()).isEqualTo(DispatchOutcomeReason.HARD_BOUNCE.name());

        List<RecruiterNotification> notes = mongoTemplate.findAll(RecruiterNotification.class);
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).getType()).isEqualTo(RecruiterNotificationType.DISPATCH_BOUNCED);
        assertThat(notes.get(0).getCandidateId()).isEqualTo("c1");
    }

    @Test
    void softBounce_noCandidateFlag_rowReasonOnly() throws Exception {
        seedContactableCandidate("c1", "Dana", "dana@example.com");
        seedSent(WS, "c1", "ref-1");

        postSigned(event("evt-1", "ref-1", "soft_bounce"));

        EmailDispatch row = mongoTemplate.findOne(
            Query.query(Criteria.where("providerMessageRef").is("ref-1")), EmailDispatch.class);
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.SENT); // no status change
        assertThat(row.getLastOutcomeReason()).isEqualTo(DispatchOutcomeReason.SOFT_BOUNCE);

        Candidate c = mongoTemplate.findById("c1", Candidate.class);
        assertThat(c.isUndeliverable()).isFalse(); // FR-018: no flag on a soft bounce
        assertThat(mongoTemplate.findAll(RecruiterNotification.class)).isEmpty();
    }

    @Test
    void duplicateEventId_singleFlagAndNotify() throws Exception {
        seedContactableCandidate("c1", "Dana", "dana@example.com");
        seedSent(WS, "c1", "ref-1");
        String body = event("evt-1", "ref-1", "bounce");

        postSigned(body);
        postSigned(body); // exact replay
        postSigned(body);

        assertThat(mongoTemplate.findAll(RecruiterNotification.class)).hasSize(1);
        List<AuthAuditEvent> audits = mongoTemplate.find(
            Query.query(Criteria.where("eventType").is(AuthEventType.EMAIL_DISPATCH_BOUNCED)), AuthAuditEvent.class);
        assertThat(audits).hasSize(1);
    }

    @Test
    void unknownProviderRef_returns200_noStateChange() throws Exception {
        seedContactableCandidate("c1", "Dana", "dana@example.com");
        seedSent(WS, "c1", "ref-1");

        postSigned(event("evt-1", "ref-UNKNOWN", "bounce"));

        EmailDispatch row = mongoTemplate.findOne(
            Query.query(Criteria.where("providerMessageRef").is("ref-1")), EmailDispatch.class);
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.SENT);
        Candidate c = mongoTemplate.findById("c1", Candidate.class);
        assertThat(c.isUndeliverable()).isFalse();
        assertThat(mongoTemplate.findAll(RecruiterNotification.class)).isEmpty();
    }

    @Test
    void crossWorkspaceRef_noStateChange() throws Exception {
        // The dispatch row + candidate live in a DIFFERENT workspace; correlation is by ref only, but the
        // candidate flip is workspace-scoped to the row's own workspace — there is no cross-workspace leak.
        seedContactableCandidate("c1", "Dana", "dana@example.com"); // ws1 candidate, no matching row
        seedSent("ws2", "c2", "ref-2"); // ws2 row, ws2 candidate (not seeded in ws1)

        postSigned(event("evt-1", "ref-2", "bounce"));

        // The ws1 candidate must be untouched (the event correlated to a ws2 row only).
        Candidate c1 = mongoTemplate.findById("c1", Candidate.class);
        assertThat(c1.isUndeliverable()).isFalse();
        // No notification was raised against ws1's candidate.
        List<RecruiterNotification> notes = mongoTemplate.findAll(RecruiterNotification.class);
        assertThat(notes).noneMatch(n -> n.getCandidateId().equals("c1"));
    }
}
