package com.cadence.feedback;

import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.FeedbackRequestStatus;
import com.cadence.domain.Member;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-002/SC-003/SC-012/SC-015(generation): generation creates exactly one request per ACTIVE participant, is
 * idempotent across repeated AND gated-concurrent sweeps (the feedbackGeneratedAt CAS + the unique
 * {interviewEventId,interviewerMemberId} index), skips a non-occurrence, and reports an uncollectible
 * deactivated interviewer.
 */
class FeedbackGenerationIT extends FeedbackItBase {

    private long requestCount() {
        return mongoTemplate.count(new Query(), FeedbackRequest.class);
    }

    private void seedPanel(String reqId, long hoursAgo, Member... interviewers) {
        SchedulingRequest req = seedBookedInterview(reqId, "cand1", hoursAgo);
        for (Member m : interviewers) {
            seedClaim(reqId, m.getId(), req.getBookedStartAt());
        }
    }

    @Test
    void generatesOneRequestPerInterviewer_andIsIdempotent() {
        configuredWorkspace();
        seedCandidate("cand1", "Dana Doe", "dana@example.com");
        Member a = member("intA@example.com", Role.INTERVIEWER);
        Member b = member("intB@example.com", Role.INTERVIEWER);
        seedPanel("req1", 4, a, b); // 4h ago > 3h generationDelay -> occurred

        scheduler.sweep();
        assertThat(requestCount()).isEqualTo(2); // SC-002: one per interviewer

        scheduler.sweep(); // SC-003: repeated sweep creates nothing new
        assertThat(requestCount()).isEqualTo(2);
        assertThat(feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, "req1"))
            .allMatch(r -> r.getStatus() == FeedbackRequestStatus.PENDING)
            .extracting(FeedbackRequest::getInterviewerMemberId)
            .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    void concurrentSweeps_gated_createExactlyOnePerInterviewer() throws Exception {
        configuredWorkspace();
        seedCandidate("cand1", "Dana Doe", "dana@example.com");
        Member a = member("intA@example.com", Role.INTERVIEWER);
        Member b = member("intB@example.com", Role.INTERVIEWER);
        seedPanel("req1", 4, a, b);

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

        assertThat(requestCount()).isEqualTo(2); // CAS + unique index: no duplicate rows under the race
    }

    @Test
    void noGeneration_forCancelledOrFutureInterview() {
        configuredWorkspace();
        seedCandidate("cand1", "Dana Doe", "dana@example.com");
        Member a = member("intA@example.com", Role.INTERVIEWER);
        // cancelled (non-occurrence) and future (not yet occurred)
        SchedulingRequest cancelled = seedInterview("reqCancel", "cand1", SchedulingStatus.CANCELLED, 4);
        seedClaim("reqCancel", a.getId(), cancelled.getBookedStartAt());
        SchedulingRequest future = seedBookedInterview("reqFuture", "cand1", -2); // starts in 2h
        seedClaim("reqFuture", a.getId(), future.getBookedStartAt());

        scheduler.sweep();
        assertThat(requestCount()).isZero(); // SC-012
    }

    @Test
    void deactivatedInterviewer_atGeneration_uncollectibleAlert_noRequest() {
        configuredWorkspace();
        seedCandidate("cand1", "Dana Doe", "dana@example.com");
        Member a = member("intA@example.com", Role.INTERVIEWER);
        deactivate(a.getId());
        seedPanel("req1", 4, a);

        scheduler.sweep();
        assertThat(requestCount()).isZero(); // SC-015: no request for a deactivated interviewer
        assertThat(mongoTemplate.count(new Query(), RecruiterNotification.class)).isEqualTo(1); // fallback alert
    }
}
