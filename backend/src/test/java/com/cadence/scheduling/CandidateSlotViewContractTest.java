package com.cadence.scheduling;

import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract B (view): GET /api/candidate/scheduling/{token} — 200 open (times only, FR-011: participant member
 * ids NEVER in the body), 200 booked, 410 expired, 400 invalid (unknown + superseded byte-identical), 429 over
 * the rate limit. {@code rate-limit-per-minute=5} for the 429 test. no-store header.
 */
@TestPropertySource(properties = "cadence.scheduling.rate-limit-per-minute=5")
class CandidateSlotViewContractTest extends SchedulingItBase {

    private static final String PART_A = "111111111111111111111111";
    private static final String PART_B = "222222222222222222222222";

    // The rate limiter keys on the source IP and the clock is frozen (one window for the whole class), so each
    // call uses a UNIQUE IP — the limiter never accumulates across unrelated test methods. The 429 test reuses
    // one IP for its burst.
    private static final AtomicInteger IP = new AtomicInteger(1);

    private OfferedSlot openSlot() {
        return slot("0", Instant.parse("2026-06-20T13:00:00Z"), Instant.parse("2026-06-20T14:00:00Z"),
            List.of(PART_A), List.of(List.of(PART_B)));
    }

    /** A view GET from a fresh, unique source IP (so the per-IP limiter window is pristine). */
    private MockHttpServletRequestBuilder view(String token) {
        return viewFrom(token, "10.0." + IP.getAndIncrement() + ".1");
    }

    private MockHttpServletRequestBuilder viewFrom(String token, String ip) {
        return get("/api/candidate/scheduling/" + token).with(r -> { r.setRemoteAddr(ip); return r; });
    }

    @Test
    void open_returns200_timesOnly_noParticipantIds_noStore() throws Exception {
        Seeded s = seedPendingRequest("cand1", "tmpl1", "Room 1", List.of(openSlot()));

        String json = mvc.perform(view(s.rawToken))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.status").value("open"))
            .andExpect(jsonPath("$.zoneHint").value("UTC"))
            .andExpect(jsonPath("$.slots[0].slotId").value("0"))
            .andExpect(jsonPath("$.slots[0].start").exists())
            .andExpect(jsonPath("$.slots[0].end").exists())
            .andExpect(jsonPath("$.slots[0].zoneId").value("UTC"))
            .andReturn().getResponse().getContentAsString();

        // FR-011 (NON-CIRCULAR): the participant member ids were seeded into the request but must NEVER
        // appear anywhere in the candidate-facing JSON (no requiredMemberIds/poolCandidates leak).
        org.assertj.core.api.Assertions.assertThat(json).doesNotContain(PART_A).doesNotContain(PART_B);
    }

    @Test
    void booked_returns200_bookedStatus() throws Exception {
        Seeded s = seedPendingRequest("cand1", "tmpl1", "Room 1", List.of(openSlot()));
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(s.request.getId())),
            new Update().set("status", SchedulingStatus.BOOKED).set("chosenSlotId", "0"),
            SchedulingRequest.class);

        mvc.perform(view(s.rawToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("booked"))
            .andExpect(jsonPath("$.bookedStart").exists());
    }

    @Test
    void expired_returns410() throws Exception {
        Seeded s = seedPendingRequest("cand1", "tmpl1", "Room 1", List.of(openSlot()));
        // Stamp expiresAt into the past (deterministic, no wall-clock sleep).
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(s.request.getId())),
            new Update().set("expiresAt", Instant.parse("2026-06-01T00:00:00Z")),
            SchedulingRequest.class);

        mvc.perform(view(s.rawToken))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.error").value("expired"));
    }

    @Test
    void unknownToken_returns400_invalid() throws Exception {
        mvc.perform(view("this-is-not-a-real-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid"));
    }

    @Test
    void supersededToken_is400_invalid_byteIdenticalToUnknown() throws Exception {
        Seeded s = seedPendingRequest("cand1", "tmpl1", "Room 1", List.of(openSlot()));
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(s.request.getId())),
            new Update().set("status", SchedulingStatus.SUPERSEDED), SchedulingRequest.class);

        String unknown = mvc.perform(view("another-bogus-token"))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        mvc.perform(view(s.rawToken))
            .andExpect(status().isBadRequest())
            .andExpect(content().json(unknown)); // byte-identical envelope -> no existence oracle
    }

    @Test
    void overRateLimit_returns429() throws Exception {
        Seeded s = seedPendingRequest("cand1", "tmpl1", "Room 1", List.of(openSlot()));
        String ip = "10.99.99.99"; // one fixed IP for the burst (frozen clock => one window)
        // limit = 5/min/ip; the 6th in the same minute is throttled.
        for (int i = 0; i < 5; i++) {
            mvc.perform(viewFrom(s.rawToken, ip)).andExpect(status().isOk());
        }
        mvc.perform(viewFrom(s.rawToken, ip))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").value("rate_limited"));
    }
}
