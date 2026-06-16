package com.cadence.scheduling;

import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.integration.EmailSender;
import com.cadence.scheduler.SchedulingReaper;
import com.cadence.service.CalendarEventService;
import com.cadence.service.CandidateErasureService;
import com.cadence.service.EmailDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F20 FR-024 / SC-009: erasing a candidate with a BOOKED interview must leave NO calendar event or usable
 * link. {@code wipe()} (synchronous, O(1)) CASes the booking to CANCELLED + releases claims + {@code $unset}s
 * the manage token + sets {@code calendarTeardownPending}; the reaper then removes the provider events async.
 */
class ErasureBookingTeardownTest extends SchedulingItBase {

    private static final String REQ_MEMBER = "111111111111111111111111";

    @Autowired CandidateErasureService erasure;
    @Autowired SchedulingReaper reaper;
    @MockBean CalendarEventService calendar;
    @MockBean EmailDispatchService dispatch;
    @MockBean EmailSender emailSender;

    @Test
    void erasingBookedCandidate_cancelsSynchronously_thenReaperRemovesEvents() {
        when(calendar.cancelBooking(eq(WS), any())).thenReturn(true);
        InterviewTemplate t = seedTemplate(REQ_MEMBER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        OfferedSlot s = slot("0", Instant.parse("2026-06-20T13:00:00Z"),
            Instant.parse("2026-06-20T14:00:00Z"), List.of(REQ_MEMBER), List.of());
        Seeded b = seedBookedRequest("cand1", t.getId(), "Room 1", s, REQ_MEMBER);

        boolean wiped = erasure.wipe(WS, "cand1", CandidateAuditOutcome.OPERATOR, "admin");
        assertThat(wiped).isTrue();

        SchedulingRequest after = mongoTemplate.findById(b.request.getId(), SchedulingRequest.class);
        assertThat(after.getStatus()).isEqualTo(SchedulingStatus.CANCELLED);     // synchronous O(1) flip
        assertThat(after.isCalendarTeardownPending()).isTrue();                   // teardown deferred to reaper
        assertThat(after.getManageTokenHash()).isNull();                          // no usable link survives
        assertThat(mongoTemplate.findAll(InterviewSlotClaim.class))
            .allMatch(c -> c.getStatus() == ClaimStatus.RELEASED);

        // The async teardown pass removes the provider events and clears the flag.
        reaper.sweep();
        verify(calendar, times(1)).cancelBooking(eq(WS), eq(b.request.getId()));
        SchedulingRequest done = mongoTemplate.findById(b.request.getId(), SchedulingRequest.class);
        assertThat(done.isCalendarTeardownPending()).isFalse();
    }
}
