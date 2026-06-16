package com.cadence.scheduling;

import com.cadence.domain.OfferedSlot;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.repository.SchedulingRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F23 recruiter one-tap release contract (FR-011/FR-020). Reuses the F20 recruiter-cancel primitive:
 * ADMIN/RECRUITER only; workspace-scoped; 409 for no booking / past interview.
 */
class RecruiterReleaseContractTest extends SchedulingItBase {

    @Autowired SchedulingRequestRepository requests;

    private String memberId;
    private String templateId;

    private void seedBase() {
        configuredWorkspace();
        memberId = member("iv@x.test", Role.RECRUITER).getId();
        templateId = seedTemplate(memberId).getId();
        seedContactableCandidate("cand1", "Dana", "dana@x.test");
    }

    private SchedulingRequest seedBooked(Duration startOffset) {
        Instant start = Instant.now(clock).plus(startOffset);
        OfferedSlot chosen = slot("0", start, start.plus(Duration.ofHours(1)), List.of(memberId), List.of());
        return seedBookedRequest("cand1", templateId, "Room", chosen, memberId).request;
    }

    @Test
    void recruiterReleasesUnconfirmedSlot() throws Exception {
        seedBase();
        SchedulingRequest b = seedBooked(Duration.ofHours(1));
        var admin = member("admin@x.test", Role.ADMIN);

        mvc.perform(post("/api/internal/candidates/{c}/scheduling/release", "cand1").cookie(cookie(admin)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("cancelled"));
        assertThat(requests.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(SchedulingStatus.CANCELLED);
    }

    @Test
    void disallowedRolesGet403() throws Exception {
        seedBase();
        seedBooked(Duration.ofHours(1));
        for (Role role : List.of(Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY)) {
            var m = member(role.name().toLowerCase() + "@x.test", role);
            mvc.perform(post("/api/internal/candidates/{c}/scheduling/release", "cand1").cookie(cookie(m)).with(csrf()))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void noBooking_returns409() throws Exception {
        seedBase();
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(post("/api/internal/candidates/{c}/scheduling/release", "cand1").cookie(cookie(rec)).with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("no_active_booking"));
    }

    @Test
    void pastInterview_returns409Ineligible() throws Exception {
        seedBase();
        seedBooked(Duration.ofMinutes(-5));
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(post("/api/internal/candidates/{c}/scheduling/release", "cand1").cookie(cookie(rec)).with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("ineligible"));
    }
}
