package com.cadence.pipeline;

import com.cadence.domain.Assignment;
import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.Member;
import com.cadence.domain.RequisitionStatus;
import com.cadence.domain.ResourceType;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F51 T029 / FR-008..FR-011: requisition management role matrix + the candidate link audit + no-oracle 404.
 */
class RequisitionContractIT extends PipelineItBase {

    @Test
    void create_adminOnly() throws Exception {
        var admin = member("admin@x.test", Role.ADMIN);
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(post("/api/internal/requisitions").cookie(cookie(admin)).with(csrf())
                .contentType("application/json").content("{\"title\":\"Backend Eng\",\"externalLabel\":\"GH-1\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("Backend Eng"))
            .andExpect(jsonPath("$.externalLabel").value("GH-1"))     // FR-011 external label surfaced
            .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(post("/api/internal/requisitions").cookie(cookie(rec)).with(csrf())
                .contentType("application/json").content("{\"title\":\"x\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void list_adminRecruiterReadOnly_200_interviewer403() throws Exception {
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        for (Role r : new Role[]{Role.ADMIN, Role.RECRUITER, Role.READ_ONLY}) {
            var m = member(r.name().toLowerCase() + "@x.test", r);
            mvc.perform(get("/api/internal/requisitions").cookie(cookie(m))).andExpect(status().isOk());
        }
        var iv = member("iv@x.test", Role.INTERVIEWER);
        mvc.perform(get("/api/internal/requisitions").cookie(cookie(iv))).andExpect(status().isForbidden());
    }

    @Test
    void update_unknownRequisition_noOracle404() throws Exception {
        var admin = member("admin@x.test", Role.ADMIN);
        mvc.perform(patch("/api/internal/requisitions/{id}", "nope").cookie(cookie(admin)).with(csrf())
                .contentType("application/json").content("{\"status\":\"CLOSED\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"))
            .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void assignHm_adminOnly() throws Exception {
        var admin = member("admin@x.test", Role.ADMIN);
        Member hm = member("hm@x.test", Role.HIRING_MANAGER);
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        mvc.perform(post("/api/internal/requisitions/{id}/assignments", "r1").cookie(cookie(admin)).with(csrf())
                .contentType("application/json").content("{\"memberId\":\"" + hm.getId() + "\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void linkCandidate_adminRecruiter_audited_readOnly403() throws Exception {
        configuredWorkspace();
        var rec = member("rec@x.test", Role.RECRUITER);
        var ro = member("ro@x.test", Role.READ_ONLY);
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        seedActive("c1", "Ada", 1, null);

        mvc.perform(put("/api/internal/candidates/{c}/requisition", "c1").cookie(cookie(rec)).with(csrf())
                .contentType("application/json").content("{\"requisitionId\":\"r1\"}"))
            .andExpect(status().isNoContent());
        long linked = mongoTemplate.count(Query.query(Criteria.where("candidateId").is("c1")
            .and("eventType").is(CandidateEventType.REQUISITION_LINKED)), CandidateAuditEvent.class);
        assertThat(linked).isEqualTo(1);

        // Re-link (move) to another requisition audits a SECOND event (set AND change are audited).
        seedRequisition("r2", "R2", RequisitionStatus.OPEN);
        mvc.perform(put("/api/internal/candidates/{c}/requisition", "c1").cookie(cookie(rec)).with(csrf())
                .contentType("application/json").content("{\"requisitionId\":\"r2\"}"))
            .andExpect(status().isNoContent());
        long afterMove = mongoTemplate.count(Query.query(Criteria.where("candidateId").is("c1")
            .and("eventType").is(CandidateEventType.REQUISITION_LINKED)), CandidateAuditEvent.class);
        assertThat(afterMove).isEqualTo(2);

        mvc.perform(put("/api/internal/candidates/{c}/requisition", "c1").cookie(cookie(ro)).with(csrf())
                .contentType("application/json").content("{\"requisitionId\":\"r1\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void requisitionWrites_fiveRoleMatrix_adminOnly() throws Exception {
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        Member hmTarget = member("hmtarget@x.test", Role.HIRING_MANAGER);
        // create / patch / assign / unassign are ADMIN-only (SC-004, contract §4): every non-admin role -> 403.
        for (Role r : new Role[]{Role.RECRUITER, Role.READ_ONLY, Role.HIRING_MANAGER, Role.INTERVIEWER}) {
            var m = member(r.name().toLowerCase() + "@x.test", r);
            mvc.perform(post("/api/internal/requisitions").cookie(cookie(m)).with(csrf())
                    .contentType("application/json").content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
            mvc.perform(patch("/api/internal/requisitions/{id}", "r1").cookie(cookie(m)).with(csrf())
                    .contentType("application/json").content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isForbidden());
            mvc.perform(post("/api/internal/requisitions/{id}/assignments", "r1").cookie(cookie(m)).with(csrf())
                    .contentType("application/json").content("{\"memberId\":\"" + hmTarget.getId() + "\"}"))
                .andExpect(status().isForbidden());
            mvc.perform(delete("/api/internal/requisitions/{id}/assignments/{aid}", "r1", "x")
                    .cookie(cookie(m)).with(csrf()))
                .andExpect(status().isForbidden());
        }
        // Admin can perform a write (sanity that the 403s above are role-driven, not a broken endpoint).
        var admin = member("admin@x.test", Role.ADMIN);
        mvc.perform(patch("/api/internal/requisitions/{id}", "r1").cookie(cookie(admin)).with(csrf())
                .contentType("application/json").content("{\"status\":\"CLOSED\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void unassignHm_adminOnly_204_andNoOracleOnUnknown() throws Exception {
        var admin = member("admin@x.test", Role.ADMIN);
        Member hm = member("hm@x.test", Role.HIRING_MANAGER);
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        mvc.perform(post("/api/internal/requisitions/{id}/assignments", "r1").cookie(cookie(admin)).with(csrf())
                .contentType("application/json").content("{\"memberId\":\"" + hm.getId() + "\"}"))
            .andExpect(status().isCreated());
        Assignment a = mongoTemplate.findOne(Query.query(Criteria.where("workspaceId").is(WS)
            .and("memberId").is(hm.getId()).and("resourceType").is(ResourceType.REQUISITION)
            .and("resourceId").is("r1")), Assignment.class);
        assertThat(a).isNotNull();
        // unknown assignment id -> byte-identical no-oracle 404
        mvc.perform(delete("/api/internal/requisitions/{id}/assignments/{aid}", "r1", "nope")
                .cookie(cookie(admin)).with(csrf()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"))
            .andExpect(jsonPath("$.message").doesNotExist());
        // real id -> 204
        mvc.perform(delete("/api/internal/requisitions/{id}/assignments/{aid}", "r1", a.getId())
                .cookie(cookie(admin)).with(csrf()))
            .andExpect(status().isNoContent());
    }

    @Test
    void assignHm_unknownMember_noOracle404() throws Exception {
        var admin = member("admin@x.test", Role.ADMIN);
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        mvc.perform(post("/api/internal/requisitions/{id}/assignments", "r1").cookie(cookie(admin)).with(csrf())
                .contentType("application/json").content("{\"memberId\":\"ghost\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"))
            .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void linkCandidate_unknownCandidate_noOracle404() throws Exception {
        var rec = member("rec@x.test", Role.RECRUITER);
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        mvc.perform(put("/api/internal/candidates/{c}/requisition", "nope").cookie(cookie(rec)).with(csrf())
                .contentType("application/json").content("{\"requisitionId\":\"r1\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }
}
