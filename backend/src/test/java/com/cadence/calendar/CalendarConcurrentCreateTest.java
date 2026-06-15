package com.cadence.calendar;

import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventDetails;
import com.cadence.domain.Member;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US2 (SC-008): two concurrent createPanelEvents for the SAME booking+member race the unique-index claim
 * — exactly ONE provider insert and ONE managedCalendarEvents row. A start latch forces genuine overlap
 * so the DuplicateKey claim path is exercised (non-vacuous, F01.1 gated-CAS pattern).
 */
class CalendarConcurrentCreateTest extends CalendarApiItBase {

    @Test
    void concurrentCreate_oneEvent_oneRow() throws Exception {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@gmail.com");
        EventDetails details = details("Interview", "Room 1",
            Instant.parse("2026-06-20T15:00:00Z"), Instant.parse("2026-06-20T16:00:00Z"), ZoneOffset.UTC);

        // Force GENUINE overlap: the stub holds BOTH provider creates until both have arrived, so the two
        // threads race the unique-index record concurrently (non-vacuous — F01.1 gated pattern).
        gcal.gate(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<PanelBookingResult> r1 = new AtomicReference<>();
        AtomicReference<PanelBookingResult> r2 = new AtomicReference<>();
        Runnable task1 = () -> { await(start); r1.set(eventService.createPanelEvents(WS, "bk", panel(m.getId()), details)); };
        Runnable task2 = () -> { await(start); r2.set(eventService.createPanelEvents(WS, "bk", panel(m.getId()), details)); };
        pool.submit(task1);
        pool.submit(task2);
        start.countDown(); // release both together
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Exactly ONE row (unique index dedup) and ONE distinct event (deterministic id), though both
        // threads called the provider. Both report idempotent success.
        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk")).hasSize(1);
        assertThat(gcal.liveEvents()).hasSize(1);
        assertThat(gcal.count("POST", "/events")).isEqualTo(2); // both raced to the provider (gate proved overlap)
        assertThat(r1.get().perMember().get(0).outcome()).isEqualTo(PanelBookingResult.MemberOutcome.CREATED);
        assertThat(r2.get().perMember().get(0).outcome()).isEqualTo(PanelBookingResult.MemberOutcome.CREATED);
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
