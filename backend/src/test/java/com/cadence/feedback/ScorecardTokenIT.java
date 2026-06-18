package com.cadence.feedback;

import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.FeedbackRequestStatus;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-001/007/008/009/021/023: the no-login token surface. Write-only (no prior content), valid submit persists
 * an ENCRYPTED payload, invalid -> 400, STATUS-before-TIME (used vs expired byte-distinct only for genuine TTL),
 * gated concurrent double-submit -> one record, rate-limited.
 */
class ScorecardTokenIT extends FeedbackItBase {

    private static final String SENTINEL = "SENTINELF32TEXTzz9";

    private String generateAndGetToken() {
        configuredWorkspace();
        seedCandidate("cand1", "Dana Doe", "dana@example.com");
        Member a = member("intA@example.com", Role.INTERVIEWER);
        seedBookedInterview("req1", "cand1", 4);
        seedClaim("req1", a.getId(), Instant.now(clock).minus(Duration.ofHours(4)));
        scheduler.sweep();
        FeedbackRequest r = feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, "req1").get(0);
        return r.getToken(); // converter-decrypted on read — the raw token the email carried
    }

    private String submitBody(String recommendation, String comment) {
        return "{\"recommendation\":\"" + recommendation + "\",\"ratings\":[{\"dimension\":\"Technical skills\","
            + "\"score\":3}],\"comment\":\"" + comment + "\"}";
    }

    @Test
    void loadBlankForm_returnsNoPriorContent() throws Exception {
        String token = generateAndGetToken();
        mvc.perform(get("/api/feedback/{t}", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state", is("FORM")))
            .andExpect(jsonPath("$.recommendationOptions[0]", is("STRONG_YES")))
            // SC-008: the blank form carries no submitted content fields.
            .andExpect(jsonPath("$.scorecard").doesNotExist())
            .andExpect(jsonPath("$.comment").doesNotExist());
    }

    @Test
    void submitValid_persistsEncryptedPayload_andIsWriteOnly() throws Exception {
        String token = generateAndGetToken();
        mvc.perform(post("/api/feedback/{t}", token).contentType("application/json")
                .content(submitBody("YES", SENTINEL)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state", is("SUBMITTED")))
            // SC-008: the submit response echoes no prior/stored content.
            .andExpect(jsonPath("$.scorecard").doesNotExist());

        // SC encryption: raw-driver read shows ciphertext (no plaintext sentinel in the stored payload).
        Document raw = mongoTemplate.getCollection("feedbackRequests").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("scorecardPayload")).doesNotContain(SENTINEL).doesNotContain("YES");
        // It decrypts back through the converter on a typed read.
        FeedbackRequest typed = feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, "req1").get(0);
        assertThat(typed.getStatus()).isEqualTo(FeedbackRequestStatus.SUBMITTED);
        assertThat(typed.getScorecardPayload()).contains(SENTINEL);
    }

    @Test
    void submitInvalid_missingRecommendation_400_nothingPersisted() throws Exception {
        String token = generateAndGetToken();
        mvc.perform(post("/api/feedback/{t}", token).contentType("application/json")
                .content("{\"ratings\":[],\"comment\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", is("invalid_scorecard")));
        FeedbackRequest typed = feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, "req1").get(0);
        assertThat(typed.getStatus()).isEqualTo(FeedbackRequestStatus.PENDING);
        assertThat(typed.getScorecardPayload()).isNull();
    }

    @Test
    void statusBeforeTime_usedAfterSubmit_expiredOnlyForPastTtl() throws Exception {
        String token = generateAndGetToken();
        // submitted -> USED (byte-identical to unknown/invalidated)
        mvc.perform(post("/api/feedback/{t}", token).contentType("application/json")
            .content(submitBody("YES", "ok"))).andExpect(status().isOk());
        mvc.perform(get("/api/feedback/{t}", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("USED")));
        // unknown token -> USED (same envelope)
        mvc.perform(get("/api/feedback/{t}", "unknown-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("USED")));

        // a genuinely past-TTL PENDING request -> EXPIRED (distinct)
        String token2 = freshPendingTokenWithPastTtl();
        mvc.perform(get("/api/feedback/{t}", token2))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state", is("EXPIRED")));
    }

    private String freshPendingTokenWithPastTtl() {
        Member b = member("intB@example.com", Role.INTERVIEWER);
        seedBookedInterview("req2", "cand1", 4);
        seedClaim("req2", b.getId(), Instant.now(clock).minus(Duration.ofHours(4)));
        scheduler.sweep();
        FeedbackRequest r = feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, "req2").get(0);
        // stamp expiry into the past (deterministic — no wall-clock sleep)
        mongoTemplate.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(r.getId())),
            new org.springframework.data.mongodb.core.query.Update().set("expiresAt", Instant.now(clock).minus(Duration.ofHours(1))),
            FeedbackRequest.class);
        return r.getToken();
    }

    @Test
    void concurrentDoubleSubmit_gated_oneRecord() throws Exception {
        String token = generateAndGetToken();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger submitted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    String resp = mvc.perform(post("/api/feedback/{t}", token).contentType("application/json")
                            .content(submitBody("YES", "c"))).andReturn().getResponse().getContentAsString();
                    if (resp.contains("SUBMITTED")) {
                        submitted.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // recorded via the count below
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        //noinspection ResultOfMethodCallIgnored
        pool.awaitTermination(20, TimeUnit.SECONDS);
        // Exactly one persisted SUBMITTED row regardless of how many threads observed SUBMITTED.
        long submittedRows = mongoTemplate.count(
            Query.query(org.springframework.data.mongodb.core.query.Criteria.where("status").is("SUBMITTED")),
            FeedbackRequest.class);
        assertThat(submittedRows).isEqualTo(1);
        assertThat(submitted.get()).isEqualTo(1); // the CAS winner is the only SUBMITTED responder
    }

    @Test
    void publicEndpoint_rateLimited_429() throws Exception {
        String token = generateAndGetToken();
        // test-profile cap is 5/min; the 6th request in the same (fixed-clock) minute is throttled.
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/feedback/{t}", token)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/feedback/{t}", token))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error", is("rate_limited")));
    }
}
