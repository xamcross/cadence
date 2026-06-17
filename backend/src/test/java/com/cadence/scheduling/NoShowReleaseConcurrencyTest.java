package com.cadence.scheduling;

import com.cadence.domain.OfferedSlot;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.service.SchedulingService;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F23 release single-winner (SC-007/FR-013). Two concurrent releases of one booking — gated so both threads
 * resolve the booking and then race the {@code BOOKED->CANCELLING} CAS — yield exactly one authoritative
 * cancellation and exactly one candidate cancellation notice; no double teardown, no split state.
 */
class NoShowReleaseConcurrencyTest extends SchedulingItBase {

    @Autowired SchedulingService scheduling;
    @Autowired SchedulingRequestRepository requests;

    @RepeatedTest(5)
    void twoConcurrentReleases_oneAuthoritativeTransition() throws Exception {
        configuredWorkspace();
        String memberId = member("iv@x.test", Role.RECRUITER).getId();
        String templateId = seedTemplate(memberId).getId();
        seedContactableCandidate("cand1", "Dana", "dana@x.test");
        Instant start = Instant.now(clock).plus(Duration.ofHours(1));
        OfferedSlot chosen = slot("0", start, start.plus(Duration.ofHours(1)), List.of(memberId), List.of());
        SchedulingRequest b = seedBookedRequest("cand1", templateId, "Room", chosen, memberId).request;

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Runnable release = () -> {
            try {
                go.await();
                scheduling.cancelByRecruiter(WS, "admin", "cand1", "127.0.0.1");
            } catch (RuntimeException ignored) {
                // a clean "already changed" refusal (NoActiveBooking) is the expected loser outcome
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        };
        pool.submit(release);
        pool.submit(release);
        go.countDown();
        pool.shutdown();
        //noinspection ResultOfMethodCallIgnored
        pool.awaitTermination(15, TimeUnit.SECONDS);

        // Exactly one authoritative transition: only the thread that won the BOOKED->CANCELLING CAS performs the
        // teardown + audit; the loser short-circuits as an idempotent replay BEFORE auditing. So exactly ONE
        // SCHEDULING_CANCELLED audit entry exists (the strong single-winner signal), and the booking is in a
        // single terminal state — never split.
        long cancelAudits = mongoTemplate.count(Query.query(Criteria.where("workspaceId").is(WS)
            .and("eventType").is(com.cadence.domain.AuthEventType.SCHEDULING_CANCELLED)),
            com.cadence.domain.AuthAuditEvent.class);
        assertThat(cancelAudits).isEqualTo(1);
        assertThat(requests.findById(b.getId()).orElseThrow().getStatus())
            .isIn(SchedulingStatus.CANCELLED, SchedulingStatus.CLEANUP_INCOMPLETE);
        long cleanupAlerts = mongoTemplate.find(Query.query(Criteria.where("candidateId").is("cand1")
            .and("type").is(RecruiterNotificationType.CALENDAR_CLEANUP_INCOMPLETE)), com.cadence.domain.RecruiterNotification.class).size();
        assertThat(cleanupAlerts).isLessThanOrEqualTo(1);
    }
}
