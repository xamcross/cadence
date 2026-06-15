package com.cadence.interview;

import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.SlotComputationRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SC-007: the deterministic, countable property is the CI gate — EXACTLY one panel availability read
 * per computation, and one cap read per required member; the member-id set handed to
 * {@code AvailabilityService} equals exactly the persisted template's required + pool members (the D8
 * isolation control — no foreign/extra ids). (Latency is not asserted as a wall-clock gate.)
 */
class RuleEnginePerfTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final LocalDate START = LocalDate.parse("2026-06-15");
    private static final LocalDate END = LocalDate.parse("2026-06-28");

    @Test
    @SuppressWarnings("unchecked")
    void singleAvailabilityRead_capReadPerRequiredMember_andExactMemberIdSet() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 30, 15, 15, 3,
            List.of("m1", "m2"), List.of(RuleEngineHarness.pool(1, "p1", "p2")));
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", LocalTime.of(9, 0), LocalTime.of(17, 0))
            .template(t);

        h.engine.compute(new SlotComputationRequest(RuleEngineHarness.WS, "t", START, END));

        // Exactly one panel read.
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(h.availability, times(1)).query(eq(RuleEngineHarness.WS), any(), any(), ids.capture());
        // Only the persisted required + pool members — never optional, never a foreign/extra id (D8).
        assertThat(ids.getValue()).containsExactlyInAnyOrder("m1", "m2", "p1", "p2");

        // One cap read per required member (2), never per day.
        verify(h.managedEvents, times(2))
            .findLiveForCap(eq(RuleEngineHarness.WS), anyString(), any(), any(), any());
    }
}
