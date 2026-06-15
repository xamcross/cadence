package com.cadence.calendar;

import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventDetails;
import com.cadence.domain.Member;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US2 (SC-008): two concurrent createPanelEvents for the SAME booking+member race the unique-index claim —
 * the gate fires on the provider POST so both threads reach Graph before either claims. Exactly ONE Graph
 * event (transactionId dedup) and ONE managedCalendarEvents row (durable unique-index guarantee, F11 D5).
 */
class MicrosoftConcurrentCreateTest extends CalendarApiItBase {

    @Test
    void concurrentCreate_oneEvent_oneRow() throws Exception {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "alex@contoso.com");
        EventDetails details = details("Interview", "Room 1",
            Instant.parse("2026-06-20T15:00:00Z"), Instant.parse("2026-06-20T16:00:00Z"), ZoneOffset.UTC);

        mscal.gate(2); // both provider POSTs held until both arrive -> genuine overlap (non-vacuous)
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<PanelBookingResult> r1 = new AtomicReference<>();
        AtomicReference<PanelBookingResult> r2 = new AtomicReference<>();
        pool.submit(() -> { await(start); r1.set(eventService.createPanelEvents(WS, "bk", panel(m.getId()), details)); });
        pool.submit(() -> { await(start); r2.set(eventService.createPanelEvents(WS, "bk", panel(m.getId()), details)); });
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk")).hasSize(1);
        assertThat(mscal.liveEvents()).hasSize(1); // transactionId dedup -> one Graph event
        assertThat(mscal.count("POST", "/events")).isEqualTo(2); // both raced to the provider (gate proved overlap)
        assertThat(r1.get().perMember().get(0).outcome()).isEqualTo(PanelBookingResult.MemberOutcome.CREATED);
        assertThat(r2.get().perMember().get(0).outcome()).isEqualTo(PanelBookingResult.MemberOutcome.CREATED);
        // Both stored the SAME server id (read-back consistency under the dedup).
        assertThat(r1.get().perMember().get(0).providerEventId())
            .isEqualTo(r2.get().perMember().get(0).providerEventId());
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
