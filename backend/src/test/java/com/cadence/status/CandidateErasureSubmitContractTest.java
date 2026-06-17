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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F30 T034 — candidate erasure-submit contract (contract B, SC-010). 202 {"status":"received"} identical
 * across {valid, unknown, malformed, erased} (no oracle); GET -> 405 (affirmative POST only); 429 at threshold.
 */
class CandidateErasureSubmitContractTest extends StatusItBase {

    @Autowired CandidateStatusService service;

    private String validToken() {
        seedCandidate("c1", "Ada", "ada@x.test");
        service.publish(WS, "c1", "actor", new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, "Onsite", "Feedback", LocalDate.now(clock).plusDays(2)));
        String link = service.statusLinkFor(WS, "c1");
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    @Test
    void erasureSubmit_indistinguishable202_acrossAllCases() throws Exception {
        configuredWorkspace();
        String valid = validToken();
        seedCandidate("c2", "Bob", "bob@x.test");
        service.publish(WS, "c2", "actor", new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, "Onsite", "Feedback", LocalDate.now(clock).plusDays(2)));
        String erasedToken = service.statusLinkFor(WS, "c2");
        erasedToken = erasedToken.substring(erasedToken.indexOf("token=") + "token=".length());
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is("c2")),
            new Update().set("erasureState", ErasureState.ERASED), com.cadence.domain.Candidate.class);

        String validBody = submit202(valid);
        String unknownBody = submit202(SecureTokens.newToken());
        String malformedBody = submit202("!!!");
        String erasedBody = submit202(erasedToken);

        assertThat(validBody).isEqualTo(unknownBody).isEqualTo(malformedBody).isEqualTo(erasedBody);
        assertThat(validBody).contains("received");
    }

    private String submit202(String token) throws Exception {
        return mvc.perform(post("/api/candidate/status/{t}/erasure-request", token))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("received"))
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void getOnErasureRequest_returns405() throws Exception {
        configuredWorkspace();
        mvc.perform(get("/api/candidate/status/{t}/erasure-request", SecureTokens.newToken()))
            .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void rateLimited_atThresholdPlusOne() throws Exception {
        configuredWorkspace();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/candidate/status/{t}/erasure-request", SecureTokens.newToken()))
                .andExpect(status().isAccepted());
        }
        mvc.perform(post("/api/candidate/status/{t}/erasure-request", SecureTokens.newToken()))
            .andExpect(status().isTooManyRequests());
    }
}
