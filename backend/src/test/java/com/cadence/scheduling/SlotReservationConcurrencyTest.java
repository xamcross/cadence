package com.cadence.scheduling;

import com.cadence.api.SchedulingExceptions;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.PanelBookingResult.MemberEventResult;
import com.cadence.domain.PanelBookingResult.MemberOutcome;
import com.cadence.domain.PanelBookingResult.PanelOutcome;
import com.cadence.integration.EmailSender;
import com.cadence.service.AvailabilityService;
import com.cadence.service.CalendarEventService;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.SlotReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * FR-012 (no double-book): two DISTINCT requests offering the SAME interviewer at the SAME start confirm
 * concurrently. A gated AvailabilityService mock forces both threads past re-validation together so they race
 * the real per-participant unique-PARTIAL-index claim CAS (NOT mocked) — exactly ONE wins (BOOKED) and the
 * other gets 409 slot_taken; exactly ONE ACTIVE InterviewSlotClaim survives.
 */
class SlotReservationConcurrencyTest extends SchedulingItBase {

    private static final String REQ_MEMBER = "111111111111111111111111";
    private static final Instant START = Instant.parse("2026-06-20T13:00:00Z");
    private static final Instant END = Instant.parse("2026-06-20T14:00:00Z");

    @Autowired SlotReservationService service;
    @MockBean AvailabilityService availability;
    @MockBean CalendarEventService calendar;
    @MockBean EmailDispatchService dispatch;
    @MockBean EmailSender emailSender;

    @Test
    void concurrentConfirm_sameInterviewerSameStart_oneWins_oneClaim() throws Exception {
        seedContactableCandidate("candA", "Dana", "dana@x.com");
        seedContactableCandidate("candB", "Erin", "erin@x.com");
        InterviewTemplate t = seedTemplate(REQ_MEMBER);

        OfferedSlot s = slot("0", START, END, List.of(REQ_MEMBER), List.of());
        Seeded a = seedPendingRequest("candA", t.getId(), "Room A", List.of(s));
        Seeded b = seedPendingRequest("candB", t.getId(), "Room B", List.of(s));

        // Gate: both threads must reach the post-availability claim CAS together so the race is non-vacuous.
        CountDownLatch arrived = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(availability.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            arrived.countDown();
            release.await(10, TimeUnit.SECONDS);
            return List.of(new MemberAvailability(REQ_MEMBER, AvailabilityStatus.DATA, List.of()));
        });
        when(calendar.createPanelEvents(eq(WS), any(), anyList(), any())).thenReturn(
            new PanelBookingResult(PanelOutcome.CREATED,
                List.of(new MemberEventResult(REQ_MEMBER, MemberOutcome.CREATED, "evt"))));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> e1 = new AtomicReference<>();
        AtomicReference<Throwable> e2 = new AtomicReference<>();
        pool.submit(() -> run(a.rawToken, e1));
        pool.submit(() -> run(b.rawToken, e2));

        // Release both once both have passed re-validation (forces a genuine claim-insert race).
        assertThat(arrived.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Exactly one threw slot_taken; the other succeeded.
        long taken = (isSlotTaken(e1.get()) ? 1 : 0) + (isSlotTaken(e2.get()) ? 1 : 0);
        long ok = (e1.get() == null ? 1 : 0) + (e2.get() == null ? 1 : 0);
        assertThat(ok).as("exactly one booking succeeded").isEqualTo(1);
        assertThat(taken).as("exactly one slot_taken").isEqualTo(1);

        // Exactly one ACTIVE claim survives for (ws, member, start).
        long active = mongoTemplate.findAll(InterviewSlotClaim.class).stream()
            .filter(c -> c.getStatus() == ClaimStatus.ACTIVE)
            .filter(c -> c.getMemberId().equals(REQ_MEMBER) && c.getStartAt().equals(START))
            .count();
        assertThat(active).isEqualTo(1);
    }

    private void run(String token, AtomicReference<Throwable> sink) {
        try {
            service.confirm(token, "0", "9.9.9.9");
        } catch (Throwable t) {
            sink.set(t);
        }
    }

    private static boolean isSlotTaken(Throwable t) {
        return t instanceof SchedulingExceptions.SlotTakenException;
    }
}
