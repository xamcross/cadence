package com.cadence.feedback;

import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.FeedbackRequestStatus;
import com.cadence.domain.Member;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-004/005/015/016/020/024: escalating reminders under a controlled clock. No reminder before the deadline;
 * L1/L2/L3 at the cadence; stop at max / on submit / on TTL; deactivated interviewer -> UNCOLLECTIBLE + alert +
 * link dies; gated concurrent per-level fire sends once. Cadence is absolute ({@code Duration.ofHours}) so a
 * workspace-zone DST change cannot flap it (SC-016).
 */
class FeedbackReminderIT extends FeedbackItBase {

    private FeedbackRequest generate(String reqId, Member interviewer) {
        seedBookedInterview(reqId, "cand1", 4);
        seedClaim(reqId, interviewer.getId(), Instant.now(clock).minus(Duration.ofHours(4)));
        scheduler.sweep();
        return feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, reqId).get(0);
    }

    private FeedbackRequest reload(String id) {
        return feedbackRepo.findById(id).orElseThrow();
    }

    private void setFarExpiry(String id) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
            new Update().set("expiresAt", Instant.now(clock).plus(Duration.ofDays(30))), FeedbackRequest.class);
    }

    @Test
    void noReminderBeforeDeadline_thenL1L2L3_stopAtMax() {
        configuredWorkspace();
        seedCandidate("cand1", "Dana", "dana@example.com");
        Member a = member("a@example.com", Role.INTERVIEWER);
        FeedbackRequest r = generate("req1", a);
        Instant t0 = Instant.now(clock);
        setFarExpiry(r.getId()); // isolate cadence from the 72h TTL (which would otherwise collide at L3)

        // before deadline (default 24h): no reminder
        clock.set(t0.plus(Duration.ofHours(23)));
        scheduler.sweep();
        assertThat(reload(r.getId()).getReminderLevelSent()).isZero(); // SC-004 lower bound

        clock.set(t0.plus(Duration.ofHours(24)));
        scheduler.sweep();
        assertThat(reload(r.getId()).getReminderLevelSent()).isEqualTo(1); // L1

        clock.set(t0.plus(Duration.ofHours(48)));
        scheduler.sweep();
        assertThat(reload(r.getId()).getReminderLevelSent()).isEqualTo(2); // L2

        clock.set(t0.plus(Duration.ofHours(72)));
        scheduler.sweep();
        FeedbackRequest afterL3 = reload(r.getId());
        assertThat(afterL3.getReminderLevelSent()).isEqualTo(3); // L3
        assertThat(afterL3.getNextReminderDueAt()).isNull(); // max reached -> no more

        clock.set(t0.plus(Duration.ofHours(120)));
        scheduler.sweep();
        assertThat(reload(r.getId()).getReminderLevelSent()).isEqualTo(3); // capped at max (3)
    }

    @Test
    void stopOnSubmit() {
        configuredWorkspace();
        seedCandidate("cand1", "Dana", "dana@example.com");
        Member a = member("a@example.com", Role.INTERVIEWER);
        FeedbackRequest r = generate("req1", a);
        setFarExpiry(r.getId());
        feedbackService.submit(r.getToken(),
            new com.cadence.api.FeedbackDtos.ScorecardSubmission("YES", java.util.List.of(), "c"), "1.1.1.1");

        clock.set(Instant.now(clock).plus(Duration.ofHours(48)));
        scheduler.sweep();
        FeedbackRequest after = reload(r.getId());
        assertThat(after.getStatus()).isEqualTo(FeedbackRequestStatus.SUBMITTED);
        assertThat(after.getReminderLevelSent()).isZero(); // SC-005: no reminder after submit
    }

    @Test
    void ttlExpiry_stopsReminders() {
        configuredWorkspace();
        seedCandidate("cand1", "Dana", "dana@example.com");
        Member a = member("a@example.com", Role.INTERVIEWER);
        FeedbackRequest r = generate("req1", a);
        // stamp expiry into the past; the reminder scan flips PENDING -> EXPIRED with no send (SC-024)
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(r.getId())),
            new Update().set("expiresAt", Instant.now(clock).minus(Duration.ofHours(1)))
                .set("nextReminderDueAt", Instant.now(clock).minus(Duration.ofMinutes(1))), FeedbackRequest.class);
        scheduler.sweep();
        FeedbackRequest after = reload(r.getId());
        assertThat(after.getStatus()).isEqualTo(FeedbackRequestStatus.EXPIRED);
        assertThat(after.getReminderLevelSent()).isZero();
        assertThat(after.getNextReminderDueAt()).isNull();
    }

    @Test
    void deactivatedAtReminder_uncollectible_alert_linkDies() throws Exception {
        configuredWorkspace();
        seedCandidate("cand1", "Dana", "dana@example.com");
        Member a = member("a@example.com", Role.INTERVIEWER);
        FeedbackRequest r = generate("req1", a);
        setFarExpiry(r.getId());
        deactivate(a.getId());

        clock.set(Instant.now(clock).plus(Duration.ofHours(24)));
        scheduler.sweep();
        FeedbackRequest after = reload(r.getId());
        assertThat(after.getStatus()).isEqualTo(FeedbackRequestStatus.UNCOLLECTIBLE); // SC-015
        assertThat(mongoTemplate.count(new Query(), RecruiterNotification.class)).isEqualTo(1); // fallback alert
        // FR-009 link cessation: the previously-live link now resolves to the byte-identical USED envelope.
        mvc.perform(get("/api/feedback/{t}", r.getToken()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("USED")));
    }

    @Test
    void concurrentPerLevel_gated_sendsOnce() throws Exception {
        configuredWorkspace();
        seedCandidate("cand1", "Dana", "dana@example.com");
        Member a = member("a@example.com", Role.INTERVIEWER);
        FeedbackRequest r = generate("req1", a);
        setFarExpiry(r.getId());
        clock.set(Instant.now(clock).plus(Duration.ofHours(24))); // deadline reached -> L1 due

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    scheduler.sweep();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        //noinspection ResultOfMethodCallIgnored
        pool.awaitTermination(20, TimeUnit.SECONDS);
        // The per-{request,level} CAS makes exactly one fire advance the level by one (SC-020).
        assertThat(reload(r.getId()).getReminderLevelSent()).isEqualTo(1);
    }
}
