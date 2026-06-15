package com.cadence.interview;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.Member;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.Role;
import com.cadence.service.AvailabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SC-006/FR-021 (QA loop-1 MAJOR): the {@code /slots} response wire shape is asserted for a NON-empty
 * result — the per-pool {@code qualifyingByPool} map (string keys), {@code requiredMemberIds}, and the
 * slot's {@code start/end/zoneId}. {@link AvailabilityService} is mocked free here (a contract test about
 * the response shape, not the calendar adapter) so real, populated slots flow through the controller.
 */
class InterviewSlotShapeContractTest extends InterviewItBase {

    @Autowired ObjectMapper mapper;
    @MockBean AvailabilityService availabilityService;

    @Test
    void slotsResponse_carriesPerPoolQualifyingAndSlotFields() throws Exception {
        configuredWorkspace(WS, "UTC", LocalTime.of(9, 0), LocalTime.of(17, 0));
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        Member req = member("req@x.com", Role.INTERVIEWER);
        Member p1 = member("p1@x.com", Role.INTERVIEWER);
        Member p2 = member("p2@x.com", Role.INTERVIEWER);

        // Everyone free (DATA, no busy) → real slots are produced and serialized.
        when(availabilityService.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<MemberAvailability> out = new ArrayList<>();
            for (String id : ids) {
                out.add(new MemberAvailability(id, AvailabilityStatus.DATA, List.of()));
            }
            return out;
        });

        String body = "{\"name\":\"Onsite\",\"durationMinutes\":60,\"slotCadenceMinutes\":60,"
            + "\"bufferBeforeMinutes\":0,\"bufferAfterMinutes\":0,\"dailyCapPerInterviewer\":100,"
            + "\"requiredMemberIds\":[\"" + req.getId() + "\"],"
            + "\"pools\":[{\"memberIds\":[\"" + p1.getId() + "\",\"" + p2.getId() + "\"],\"n\":1}]}";
        String created = mvc.perform(post("/api/internal/interview-templates").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).get("id").asText();

        mvc.perform(post("/api/internal/interview-templates/" + id + "/slots").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rangeStart\":\"2026-06-15\",\"rangeEnd\":\"2026-06-15\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.slots[0].start").exists())
            .andExpect(jsonPath("$.slots[0].end").exists())
            .andExpect(jsonPath("$.slots[0].zoneId").value("UTC"))
            .andExpect(jsonPath("$.slots[0].requiredMemberIds[0]").value(req.getId()))
            // per-pool qualifying map keyed by pool index ("0"); both pool members free → both qualify.
            .andExpect(jsonPath("$.slots[0].qualifyingByPool['0']", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$.unschedulable", org.hamcrest.Matchers.hasSize(0)));
    }
}
