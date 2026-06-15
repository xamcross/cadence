package com.cadence.gdpr;

import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.ErasureState;
import com.cadence.service.CandidateErasureService;
import jakarta.servlet.http.Cookie;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T030 / US2 / SC-002/SC-005/SC-006/FR-009: the shared wipe. */
class CandidateErasureIntegrationTest extends GdprItBase {

    @Autowired CandidateErasureService erasureService;

    @Test
    void erasure_deIdentifies_destroysEmailKey_andAuditsOnce() throws Exception {
        Candidate c = seedCandidate("Alice Smith", "alice@example.com", "+15551234567");
        Cookie admin = adminCookie();

        mvc.perform(post("/api/internal/candidates/{id}/erasure", c.getId()).cookie(admin).with(csrf()))
            .andExpect(status().isOk());

        // Read back through the app converter: PII decrypts to the marker.
        Candidate after = mongoTemplate.findById(c.getId(), Candidate.class);
        assertThat(after).isNotNull();
        assertThat(after.getName()).isEqualTo("[ERASED]");
        assertThat(after.getEmail()).isEqualTo("[ERASED]");
        assertThat(after.getPhone()).isEqualTo("[ERASED]");
        assertThat(after.getErasureState()).isEqualTo(ErasureState.ERASED);

        // Raw driver: PII is ciphertext (not the literal marker, not the original), and emailHash KEY ABSENT.
        Document raw = mongoTemplate.getCollection("candidates").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("email")).isNotEqualTo("alice@example.com").isNotEqualTo("[ERASED]");
        assertThat(raw.containsKey("emailHash")).isFalse();

        // Non-findable by the former email (recompute the hash -> no match).
        assertThat(candidateService_findByEmail("alice@example.com")).isEmpty();

        // Exactly one ERASURE_COMPLETED audit (plus the RECORD_CREATED from seeding).
        List<CandidateAuditEvent> log = mongoTemplate.findAll(CandidateAuditEvent.class);
        assertThat(log).filteredOn(e -> e.getEventType() == CandidateEventType.ERASURE_COMPLETED).hasSize(1);
        assertThat(log).filteredOn(e -> e.getEventType() == CandidateEventType.ERASURE_COMPLETED)
            .allMatch(e -> e.getOutcome() == CandidateAuditOutcome.OPERATOR);
    }

    @Test
    void erasure_isIdempotent_secondCallNoOpNoSecondAudit() throws Exception {
        Candidate c = seedCandidate("Bob", "bob@example.com", "+15550000001");
        Cookie admin = adminCookie();
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/internal/candidates/{id}/erasure", c.getId()).cookie(admin).with(csrf()))
                .andExpect(status().isOk());
        }
        long completed = mongoTemplate.findAll(CandidateAuditEvent.class).stream()
            .filter(e -> e.getEventType() == CandidateEventType.ERASURE_COMPLETED).count();
        assertThat(completed).isEqualTo(1);
    }

    @Test
    void erasure_unknownId_returns200_byteIdenticalToReal_noOracle() throws Exception {
        Candidate c = seedCandidate("Carol", "carol@example.com", "+15550000002");
        Cookie admin = adminCookie();

        MvcResult real = mvc.perform(post("/api/internal/candidates/{id}/erasure", c.getId())
            .cookie(admin).with(csrf())).andExpect(status().isOk()).andReturn();
        MvcResult unknown = mvc.perform(post("/api/internal/candidates/{id}/erasure", "0123456789abcdef01234567")
            .cookie(admin).with(csrf())).andExpect(status().isOk()).andReturn();
        MvcResult alreadyErased = mvc.perform(post("/api/internal/candidates/{id}/erasure", c.getId())
            .cookie(admin).with(csrf())).andExpect(status().isOk()).andReturn();

        String body = real.getResponse().getContentAsString();
        assertThat(unknown.getResponse().getContentAsString()).isEqualTo(body);
        assertThat(alreadyErased.getResponse().getContentAsString()).isEqualTo(body);
    }

    @Test
    void concurrentErasure_singleWipe_singleAudit() throws Exception {
        Candidate c = seedCandidate("Dave", "dave@example.com", "+15550000003");
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    erasureService.wipe(WS, c.getId(), CandidateAuditOutcome.OPERATOR, "admin1");
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(20, TimeUnit.SECONDS);
        pool.shutdownNow();

        long completed = mongoTemplate.findAll(CandidateAuditEvent.class).stream()
            .filter(e -> e.getEventType() == CandidateEventType.ERASURE_COMPLETED).count();
        assertThat(completed).isEqualTo(1); // exactly one CAS winner; losers append nothing
    }

    @Test
    void erasedThenReApplied_freshIndependentRecord_noResurrection() throws Exception {
        Candidate first = seedCandidate("Eve", "eve@example.com", "+15550000004");
        mvc.perform(post("/api/internal/candidates/{id}/erasure", first.getId()).cookie(adminCookie()).with(csrf()))
            .andExpect(status().isOk());

        Candidate fresh = seedCandidate("Eve Again", "eve@example.com", "+15550000099");
        assertThat(fresh.getId()).isNotEqualTo(first.getId());
        assertThat(fresh.getErasureState()).isEqualTo(ErasureState.ACTIVE);
        // The fresh record is findable by the email; the erased one is not.
        List<Candidate> byEmail = candidateService_findByEmail("eve@example.com");
        assertThat(byEmail).extracting(Candidate::getId).containsExactly(fresh.getId());
    }

    private List<Candidate> candidateService_findByEmail(String email) {
        Query q = Query.query(Criteria.where("workspaceId").is(WS)
            .and("emailHash").is(piiCrypto.emailHash(email)));
        return mongoTemplate.find(q, Candidate.class);
    }

    @SuppressWarnings("unused")
    private Optional<?> keep() { return Optional.empty(); }
}
