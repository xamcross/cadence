package com.cadence.pipeline;

import com.cadence.domain.Candidate;
import com.cadence.domain.ErasureState;
import com.cadence.domain.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F51 T035 / FR-015..FR-020 / SC-006: bulk actions — per-candidate outcomes, a single coarse byte-identical
 * skip reason across all non-contactable causes, the selection cap, and the role gate.
 */
class PipelineBulkIT extends PipelineItBase {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void updateEmail_mixedSelection_coarseSkip_byteIdentical() throws Exception {
        configuredWorkspace();
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            seedActive("ok" + i, "Ok" + i, 1, null);   // contactable (CONSENT)
            ids.add("ok" + i);
        }
        // Two distinct non-contactable causes -> must both report the SAME coarse reason.
        seedCandidate("erased", "Er", "S", NOW, null, ErasureState.ERASED, null);
        Candidate wd = seedActive("withdrawn", "Wd", 1, null);
        wd.setBasisWithdrawn(true);
        mongoTemplate.save(wd);
        ids.add("erased");
        ids.add("withdrawn");

        var rec = member("rec@x.test", Role.RECRUITER);
        String body = "{\"action\":\"SEND_UPDATE_EMAIL\",\"candidateIds\":"
            + json.writeValueAsString(ids) + "}";
        String resp = mvc.perform(post("/api/internal/pipeline/bulk").cookie(cookie(rec)).with(csrf())
                .contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode results = json.readTree(resp).get("results");
        long enqueued = toList(results).stream().filter(r -> r.get("outcome").asText().equals("ENQUEUED")).count();
        List<String> skipReasons = toList(results).stream()
            .filter(r -> r.get("outcome").asText().equals("SKIPPED"))
            .map(r -> r.get("reason").asText()).collect(Collectors.toList());
        assertThat(enqueued).isEqualTo(6);
        assertThat(skipReasons).containsExactlyInAnyOrder("not_contactable", "not_contactable");

        // SC-006: 0 messages to non-contactable candidates — only the 6 contactable produced a dispatch row;
        // the erased + withdrawn candidates produced none (the gate fail-closed before any enqueue).
        long dispatchRows = mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(),
            com.cadence.domain.EmailDispatch.class);
        assertThat(dispatchRows).isEqualTo(6);
    }

    @Test
    void overLimit_rejectedBeforeAnyCandidate() throws Exception {
        var rec = member("rec@x.test", Role.RECRUITER);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) ids.add("x" + i);    // > bulkMax (100)
        String body = "{\"action\":\"SEND_UPDATE_EMAIL\",\"candidateIds\":" + json.writeValueAsString(ids) + "}";
        mvc.perform(post("/api/internal/pipeline/bulk").cookie(cookie(rec)).with(csrf())
                .contentType("application/json").content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("selection_too_large"));
    }

    @Test
    void atLimitFiftyContactable_allEnqueued() throws Exception {
        configuredWorkspace();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) { seedActive("b" + i, "B" + i, 1, null); ids.add("b" + i); }
        var rec = member("rec@x.test", Role.RECRUITER);
        String body = "{\"action\":\"SEND_UPDATE_EMAIL\",\"candidateIds\":" + json.writeValueAsString(ids) + "}";
        String resp = mvc.perform(post("/api/internal/pipeline/bulk").cookie(cookie(rec)).with(csrf())
                .contentType("application/json").content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long enqueued = toList(json.readTree(resp).get("results")).stream()
            .filter(r -> r.get("outcome").asText().equals("ENQUEUED")).count();
        assertThat(enqueued).isEqualTo(50);
    }

    @Test
    void malformedBody_400_invalidRequest() throws Exception {
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(post("/api/internal/pipeline/bulk").cookie(cookie(rec)).with(csrf())
                .contentType("application/json").content("{ not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void allNonContactableCauses_byteIdenticalSkip() throws Exception {
        configuredWorkspace();
        // One candidate per distinct deny cause + an unknown id — FR-018/SC-006 require the SAME coarse reason
        // across {erased, withdrawn, over-retention, undeliverable, no-consent} AND unknown-at-execution (no
        // distinct not_found oracle for a real candidate — Security review #1).
        seedCandidate("erased", "Er", "S", NOW, null, ErasureState.ERASED, null);
        Candidate wd = seedActive("withdrawn", "Wd", 1, null); wd.setBasisWithdrawn(true); mongoTemplate.save(wd);
        Candidate nb = seedActive("nobasis", "Nb", 1, null); nb.setLawfulBasis(null); mongoTemplate.save(nb);
        Candidate ret = seedActive("overret", "Or", 1, null); ret.setRetentionFlagged(true); mongoTemplate.save(ret);
        Candidate und = seedActive("undeliv", "Un", 1, null); und.setUndeliverable(true); mongoTemplate.save(und);
        // "ghost" is never seeded -> unknown at execution.
        List<String> ids = List.of("erased", "withdrawn", "nobasis", "overret", "undeliv", "ghost");

        var rec = member("rec@x.test", Role.RECRUITER);
        String body = "{\"action\":\"SEND_UPDATE_EMAIL\",\"candidateIds\":" + json.writeValueAsString(ids) + "}";
        String resp = mvc.perform(post("/api/internal/pipeline/bulk").cookie(cookie(rec)).with(csrf())
                .contentType("application/json").content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<JsonNode> results = toList(json.readTree(resp).get("results"));
        assertThat(results).hasSize(6);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.get("outcome").asText()).isEqualTo("SKIPPED");
            assertThat(r.get("reason").asText()).isEqualTo("not_contactable"); // byte-identical across all causes
        });
        // 0 messages to any non-contactable candidate (the gate fail-closed before any enqueue).
        assertThat(mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(),
            com.cadence.domain.EmailDispatch.class)).isZero();
    }

    @Test
    void schedulingLink_initiateFailure_collapsesToCoarseSkip_noPayloadLeak() throws Exception {
        configuredWorkspace();
        seedActive("c1", "Ada", 1, null);   // contactable: passes the gate, so the SKIP can only come from initiate
        var rec = member("rec@x.test", Role.RECRUITER);
        // A non-existent templateId forces SchedulingService.initiate(...) to throw. The bulk catch is
        // RuntimeException-broad, so EVERY initiate failure (incl. UnschedulableRequiredException, whose message
        // carries interviewer member ids) collapses to the SAME coarse skip; BulkResult exposes only
        // {candidateId, outcome, reason}, so no member-id payload can ever reach the body (Security review #2).
        String body = "{\"action\":\"SEND_SCHEDULING_LINK\",\"candidateIds\":[\"c1\"],"
            + "\"templateId\":\"no-such-template\",\"rangeStart\":\"2026-07-01\",\"rangeEnd\":\"2026-07-08\"}";
        String resp = mvc.perform(post("/api/internal/pipeline/bulk").cookie(cookie(rec)).with(csrf())
                .contentType("application/json").content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode r = json.readTree(resp).get("results").get(0);
        assertThat(r.get("outcome").asText()).isEqualTo("SKIPPED");
        assertThat(r.get("reason").asText()).isEqualTo("not_contactable");
        // The result object structurally exposes ONLY the three coarse fields — no member-id / payload field.
        List<String> fields = new ArrayList<>();
        r.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("candidateId", "outcome", "reason");
        // No partial scheduling side effect from the failed initiate.
        assertThat(mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(),
            com.cadence.domain.SchedulingRequest.class)).isZero();
    }

    @Test
    void bulk_refusedForReadOnly_hm_interviewer() throws Exception {
        String body = "{\"action\":\"SEND_UPDATE_EMAIL\",\"candidateIds\":[\"c1\"]}";
        for (Role r : new Role[]{Role.READ_ONLY, Role.HIRING_MANAGER, Role.INTERVIEWER}) {
            var m = member(r.name().toLowerCase() + "@x.test", r);
            mvc.perform(post("/api/internal/pipeline/bulk").cookie(cookie(m)).with(csrf())
                    .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        }
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }
}
