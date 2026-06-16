package com.cadence.scheduling;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingMode;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.scheduler.NoShowDefenseScheduler;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F23 reschedule resets the cascade (FR-003). A reschedule round is a NEW BOOKED row with null cascade fields,
 * so it runs a FRESH cascade for the new time, and its reminder (a distinct {@code scheduledFor}) is not
 * suppressed by the prior round's F22 outbox idempotency key. The parent (now RESCHEDULED) is excluded.
 */
class RescheduleResetsCascadeTest extends SchedulingItBase {

    @Autowired NoShowDefenseScheduler scheduler;
    @Autowired SchedulingRequestRepository requests;

    @Test
    void freshRoundGetsItsOwnReminder_notSuppressedByPriorRound() {
        configuredWorkspace();
        String memberId = member("iv@x.test", com.cadence.domain.Role.RECRUITER).getId();
        String templateId = seedTemplate(memberId).getId();
        seedContactableCandidate("cand1", "Dana", "dana@x.test");

        // Parent round: already confirmed for an earlier time, then superseded by a committed reschedule.
        Instant oldStart = Instant.now(clock).plus(Duration.ofHours(1));
        OfferedSlot oldSlot = slot("0", oldStart, oldStart.plus(Duration.ofHours(1)), List.of(memberId), List.of());
        SchedulingRequest parent = seedBookedRequest("cand1", templateId, "Room", oldSlot, memberId).request;
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(parent.getId())),
            new Update().set("status", SchedulingStatus.RESCHEDULED)
                .set("confirmationRequestedAt", Instant.now(clock).minus(Duration.ofHours(22)))
                .set("candidateConfirmedAt", Instant.now(clock).minus(Duration.ofHours(21))),
            SchedulingRequest.class);
        // A prior REMINDER_24H dispatch row from that round (a DIFFERENT idempotency key / scheduledFor).
        mongoTemplate.getCollection("emailDispatches").insertOne(new Document()
            .append("workspaceId", WS).append("candidateId", "cand1")
            .append("messageType", EmailMessageType.REMINDER_24H.name())
            .append("idempotencyKey", "PRIOR_ROUND_KEY").append("status", "SENT"));

        // Fresh reschedule round: a NEW BOOKED row with null cascade fields, new start within the 24h lead.
        Instant newStart = Instant.now(clock).plus(Duration.ofHours(20));
        OfferedSlot newSlot = slot("0", newStart, newStart.plus(Duration.ofHours(1)), List.of(memberId), List.of());
        SchedulingRequest child = seedBookedRequest("cand1", templateId, "Room", newSlot, memberId).request;
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(child.getId())),
            new Update().set("mode", SchedulingMode.RESCHEDULE).set("parentRequestId", parent.getId())
                .set("rootRequestId", parent.getId()), SchedulingRequest.class);

        scheduler.sweep();

        SchedulingRequest after = requests.findById(child.getId()).orElseThrow();
        assertThat(after.getConfirmationRequestedAt()).isNotNull();   // fresh cascade ran for the new time
        assertThat(after.getConfirmTokenHash()).isNotNull();
        // The new reminder was NOT suppressed by the prior round's key -> a second REMINDER_24H row exists.
        long reminders = mongoTemplate.count(Query.query(Criteria.where("candidateId").is("cand1")
            .and("messageType").is(EmailMessageType.REMINDER_24H)), "emailDispatches");
        assertThat(reminders).isEqualTo(2);
        // The parent (RESCHEDULED) is excluded from the cascade.
        assertThat(requests.findById(parent.getId()).orElseThrow().getEscalatedAt()).isNull();
    }
}
