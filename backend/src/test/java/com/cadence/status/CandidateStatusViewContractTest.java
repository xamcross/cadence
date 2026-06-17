package com.cadence.status;

import com.cadence.api.CandidateStatusDtos.PublishStatusRequest;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.security.SecureTokens;
import com.cadence.service.CandidateStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F30 T013 — candidate view contract (contract A). STATELESS chain, no csrf, public-by-token. 200 shape per
 * displayState; byte-identical 404 across {unknown, malformed, erased} (SC-007); 429 at the test-profile
 * threshold+1 (6th call — application-test.yml caps at 5/min); {@code Cache-Control: no-store} everywhere.
 */
class CandidateStatusViewContractTest extends StatusItBase {

    @Autowired CandidateStatusService service;

    /** Publish a status for the candidate and return the raw status token (re-derived from the link). */
    private String publishAndToken(String candidateId, CandidateStatusOutcome outcome, LocalDate expected) {
        service.publish(WS, candidateId, "actor", new PublishStatusRequest(outcome,
            outcome == CandidateStatusOutcome.IN_PROGRESS ? "Onsite interview" : "Concluded",
            outcome == CandidateStatusOutcome.IN_PROGRESS ? "We are collecting feedback." : "Thank you for your time.",
            expected));
        String link = service.statusLinkFor(WS, candidateId);
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    @Test
    void publishedInProgress_returns200Shape_andNoStore() throws Exception {
        configuredWorkspace("Europe/London");
        seedCandidate("c1", "Ada", "ada@x.test");
        String token = publishAndToken("c1", CandidateStatusOutcome.IN_PROGRESS, LocalDate.now(clock).plusDays(2));

        mvc.perform(get("/api/candidate/status/{t}", token))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.displayState").value("PUBLISHED"))
            .andExpect(jsonPath("$.stage").value("Onsite interview"))
            .andExpect(jsonPath("$.nextStep").value("We are collecting feedback."))
            .andExpect(jsonPath("$.expectedDate").exists())
            .andExpect(jsonPath("$.outcome").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.workspaceZone").value("Europe/London"));
    }

    @Test
    void pastDate_returnsPastDateState() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        String token = publishAndToken("c1", CandidateStatusOutcome.IN_PROGRESS, LocalDate.now(clock).minusDays(1));

        mvc.perform(get("/api/candidate/status/{t}", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayState").value("PAST_DATE"))
            .andExpect(jsonPath("$.stage").value("Onsite interview"))
            .andExpect(jsonPath("$.expectedDate").exists());
    }

    @Test
    void terminal_returnsTerminalState() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        String token = publishAndToken("c1", CandidateStatusOutcome.COMPLETE_REJECTED, null);

        mvc.perform(get("/api/candidate/status/{t}", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayState").value("TERMINAL"))
            .andExpect(jsonPath("$.outcome").value("COMPLETE_REJECTED"));
    }

    @Test
    void noStatusPublished_returnsUnderReview_withNoFields() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        // Provision a token without publishing (statusLinkFor mints lazily on first need).
        String link = service.statusLinkFor(WS, "c1");
        String token = link.substring(link.indexOf("token=") + "token=".length());

        mvc.perform(get("/api/candidate/status/{t}", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayState").value("UNDER_REVIEW"))
            .andExpect(jsonPath("$.stage").doesNotExist())
            .andExpect(jsonPath("$.nextStep").doesNotExist())
            .andExpect(jsonPath("$.expectedDate").doesNotExist());
    }

    @Test
    void unknownMalformedAndErased_areByteIdentical404() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        String token = publishAndToken("c1", CandidateStatusOutcome.IN_PROGRESS, LocalDate.now(clock).plusDays(2));
        // Now erase the candidate (flip erasureState + clear token hash so the old token no longer resolves).
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is("c1")),
            new Update().set("erasureState", ErasureState.ERASED), com.cadence.domain.Candidate.class);

        String unknownBody = mvc.perform(get("/api/candidate/status/{t}", SecureTokens.newToken()))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
        String malformedBody = mvc.perform(get("/api/candidate/status/{t}", "!!!not-a-token!!!"))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
        // The erased candidate still has its (now stale) token hash, but erasureState!=ACTIVE -> same 404.
        String erasedBody = mvc.perform(get("/api/candidate/status/{t}", token))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        assertThat(unknownBody).isEqualTo(malformedBody).isEqualTo(erasedBody);
        assertThat(unknownBody).contains("not_found");
    }

    @Test
    void rateLimited_atThresholdPlusOne() throws Exception {
        configuredWorkspace();
        // application-test.yml caps the candidate endpoints at 5 requests/minute/IP -> the 6th 429s.
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/candidate/status/{t}", SecureTokens.newToken()))
                .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/candidate/status/{t}", SecureTokens.newToken()))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").value("rate_limited"));
    }
}
