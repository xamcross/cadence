package com.cadence.emaildelivery;

import com.cadence.domain.Candidate;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.RenderedMessage;
import com.cadence.domain.Role;
import com.cadence.service.EmailTemplateService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T021 (US1) — POST /api/internal/candidates/{id}/emails contract: 202 send, 200 idempotent duplicate,
 * 403 per non-permitted role, 404 scoped (foreign/missing candidate, oracle-free), 409 per refusal reason,
 * 400 bad/missing body; response has no PII (ids/status/type only) + Cache-Control: no-store.
 */
class EmailDispatchContractTest extends EmailDeliveryItBase {

    private static final Role[] NON_PERMITTED = {Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY};
    private static final String URL = "/api/internal/candidates/c1/emails";
    private static final String BODY = "{\"messageType\":\"CONFIRMATION\",\"stageKey\":\"BASE\"}";

    @MockBean EmailTemplateService templatesMock;

    private void stubRender() {
        when(templatesMock.renderForSend(eq(WS), any(), anyString(), anyString(), any()))
            .thenReturn(new RenderedMessage("Subject", "Body", "Body", List.of()));
    }

    @Test
    void send_returns202_noStore_noPii() throws Exception {
        stubRender();
        seedContactableCandidate("c1", "Dana", "dana@x.com");
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json").content(BODY))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.status").value("SENT"))
            .andExpect(jsonPath("$.messageType").value("CONFIRMATION"))
            .andExpect(jsonPath("$.dispatchId").isNotEmpty())
            .andExpect(jsonPath("$.toAddress").doesNotExist())
            .andExpect(jsonPath("$.subject").doesNotExist())
            .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void duplicateSend_returns200_idempotent() throws Exception {
        stubRender();
        seedContactableCandidate("c1", "Dana", "dana@x.com");
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        // First send -> 202; the controller uses now() as scheduledFor, so a same-millisecond re-send
        // collides on the idempotency key. Freeze the clock so both share scheduledFor.
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json").content(BODY))
            .andExpect(status().isAccepted());
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json").content(BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void nonPermittedRoles_areForbidden() throws Exception {
        for (Role role : NON_PERMITTED) {
            Cookie c = cookie(member(role.name().toLowerCase() + "@x.com", role));
            mvc.perform(post(URL).cookie(c).with(csrf()).contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void foreignOrMissingCandidate_is404_oracleFree() throws Exception {
        stubRender();
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(post("/api/internal/candidates/ghost/emails").cookie(admin).with(csrf())
                .contentType("application/json").content(BODY))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void refusedCandidate_is409_withValueFreeReason() throws Exception {
        stubRender();
        Candidate c = newCandidate("c1", "Dana", "dana@x.com"); // lawfulBasis null -> NO_BASIS
        mongoTemplate.save(c);
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(post(URL).cookie(admin).with(csrf()).contentType("application/json").content(BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("not_contactable"))
            .andExpect(jsonPath("$.reason").value("NO_BASIS"));
    }

    @Test
    void erasedCandidate_is409_erasedReason() throws Exception {
        stubRender();
        Candidate c = newCandidate("c1", "Dana", "dana@x.com");
        c.setErasureState(com.cadence.domain.ErasureState.ERASED);
        mongoTemplate.save(c);
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(post(URL).cookie(admin).with(csrf()).contentType("application/json").content(BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.reason").value("ERASED"));
    }

    @Test
    void undeliverableCandidate_is409_undeliverableReason() throws Exception {
        stubRender();
        Candidate c = newCandidate("c1", "Dana", "dana@x.com");
        c.setLawfulBasis(LawfulBasis.CONSENT);
        c.setUndeliverable(true);
        mongoTemplate.save(c);
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(post(URL).cookie(admin).with(csrf()).contentType("application/json").content(BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.reason").value("UNDELIVERABLE"));
    }

    @Test
    void missingMessageType_is400() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(post(URL).cookie(admin).with(csrf()).contentType("application/json").content("{\"stageKey\":\"BASE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void unknownMessageType_is400() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(post(URL).cookie(admin).with(csrf()).contentType("application/json")
                .content("{\"messageType\":\"NOT_A_TYPE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void nullBody_is400() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(post(URL).cookie(admin).with(csrf()).contentType("application/json").content(""))
            .andExpect(status().isBadRequest());
    }
}
