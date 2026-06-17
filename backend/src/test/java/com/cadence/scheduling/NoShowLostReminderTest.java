package com.cadence.scheduling;

import com.cadence.domain.OfferedSlot;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingRequest;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.service.EmailDispatchService;
import com.cadence.scheduler.NoShowDefenseScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * F23 D8 honest bound: a reminder lost because the dispatch enqueue throws AFTER the stage-1 CAS commits is
 * NOT a silent no-show — the stage-2 escalation still fires on {@code confirmationRequestedAt != null}, so the
 * recruiter is alerted regardless. Duplicates remain impossible (the per-stage CAS).
 */
class NoShowLostReminderTest extends SchedulingItBase {

    @Autowired NoShowDefenseScheduler scheduler;
    @Autowired SchedulingRequestRepository requests;
    @MockBean EmailDispatchService dispatch;

    @Test
    void lostReminder_stillEscalates() {
        configuredWorkspace();
        String memberId = member("iv@x.test", Role.RECRUITER).getId();
        String templateId = seedTemplate(memberId).getId();
        seedContactableCandidate("cand1", "Dana", "dana@x.test");
        // The reminder enqueue throws (e.g. SMTP/render down) AFTER the stage-1 claim is committed.
        when(dispatch.enqueue(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("dispatch down"));

        Instant start = Instant.now(clock).plus(Duration.ofHours(1)); // within both the 24h lead and 2h deadline
        OfferedSlot chosen = slot("0", start, start.plus(Duration.ofHours(1)), List.of(memberId), List.of());
        SchedulingRequest b = seedBookedRequest("cand1", templateId, "Room", chosen, memberId).request;

        scheduler.sweep(); // stage 1 claims (reminder enqueue throws, caught) + stage 2 escalates

        SchedulingRequest after = requests.findById(b.getId()).orElseThrow();
        assertThat(after.getConfirmationRequestedAt()).isNotNull(); // the claim committed despite the lost email
        assertThat(after.getEscalatedAt()).isNotNull();             // escalation caught it (D8 safety-net)
    }
}
