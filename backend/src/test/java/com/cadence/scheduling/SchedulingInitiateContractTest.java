package com.cadence.scheduling;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.Candidate;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.Member;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.Role;
import com.cadence.service.AvailabilityService;
import com.cadence.service.EmailDispatchService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract A (T-initiate): POST /api/internal/candidates/{id}/scheduling — 201 happy + no-store, 422 no_slots,
 * 409 not_contactable, 404 scoped (foreign candidate/template, oracle-free), 403 for the three non-permitted
 * roles + 401 unauthenticated. {@link AvailabilityService}/{@link EmailDispatchService} are mocked so the
 * full HTTP path runs without a live calendar/email backend.
 */
class SchedulingInitiateContractTest extends SchedulingItBase {

    private static final Role[] NON_PERMITTED = {Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY};

    @MockBean AvailabilityService availability;
    @MockBean EmailDispatchService dispatch;

    private void everyoneFree() {
        when(availability.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<MemberAvailability> out = new ArrayList<>();
            for (String id : ids) out.add(new MemberAvailability(id, AvailabilityStatus.DATA, List.of()));
            return out;
        });
    }

    private String body(String templateId) {
        return "{\"templateId\":\"" + templateId + "\",\"locationText\":\"Room 1\","
            + "\"rangeStart\":\"2026-06-15\",\"rangeEnd\":\"2026-06-16\"}";
    }

    @Test
    void happyPath_returns201_noStore_idsOnly() throws Exception {
        configuredWorkspace();
        everyoneFree();
        Member req = member("req@x.com", Role.INTERVIEWER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        InterviewTemplate t = seedTemplate(req.getId());
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));

        mvc.perform(post("/api/internal/candidates/cand1/scheduling").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(t.getId())))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.schedulingRequestId").isNotEmpty())
            .andExpect(jsonPath("$.status").value("PENDING_SELECTION"))
            .andExpect(jsonPath("$.offeredSlotCount").isNumber())
            .andExpect(jsonPath("$.sentAt").exists())
            .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void noSlots_returns422() throws Exception {
        configuredWorkspace();
        Member req = member("req@x.com", Role.INTERVIEWER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        InterviewTemplate t = seedTemplate(req.getId());
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));

        when(availability.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<MemberAvailability> out = new ArrayList<>();
            for (String id : ids) {
                out.add(new MemberAvailability(id, AvailabilityStatus.DATA, List.of(
                    new BusyInterval(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")))));
            }
            return out;
        });

        mvc.perform(post("/api/internal/candidates/cand1/scheduling").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(t.getId())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("no_slots"));
    }

    @Test
    void notContactable_returns409() throws Exception {
        configuredWorkspace();
        everyoneFree();
        Member req = member("req@x.com", Role.INTERVIEWER);
        Candidate c = newCandidate("cand1", "Dana", "dana@x.com"); // no basis -> not contactable
        mongoTemplate.save(c);
        InterviewTemplate t = seedTemplate(req.getId());
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));

        mvc.perform(post("/api/internal/candidates/cand1/scheduling").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(t.getId())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("not_contactable"));
    }

    @Test
    void foreignCandidate_is404_oracleFree() throws Exception {
        configuredWorkspace();
        everyoneFree();
        Member req = member("req@x.com", Role.INTERVIEWER);
        InterviewTemplate t = seedTemplate(req.getId());
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));

        mvc.perform(post("/api/internal/candidates/ghost/scheduling").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(t.getId())))
            .andExpect(status().isNotFound());
    }

    @Test
    void foreignTemplate_is404_oracleFree() throws Exception {
        configuredWorkspace();
        everyoneFree();
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));

        mvc.perform(post("/api/internal/candidates/cand1/scheduling").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("000000000000000000000000")))
            .andExpect(status().isNotFound());
    }

    @Test
    void missingTemplateId_is400() throws Exception {
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        mvc.perform(post("/api/internal/candidates/cand1/scheduling").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"locationText\":\"Room 1\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void nonPermittedRoles_areForbidden() throws Exception {
        for (Role role : NON_PERMITTED) {
            Cookie c = cookie(member(role.name().toLowerCase() + "@x.com", role));
            mvc.perform(post("/api/internal/candidates/cand1/scheduling").cookie(c).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body("t")))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void unauthenticated_is401() throws Exception {
        mvc.perform(post("/api/internal/candidates/cand1/scheduling").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body("t")))
            .andExpect(status().isUnauthorized());
    }
}
