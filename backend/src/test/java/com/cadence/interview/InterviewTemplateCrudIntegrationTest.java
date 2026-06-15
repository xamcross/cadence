package com.cadence.interview;

import com.cadence.domain.AuthEventType;
import com.cadence.domain.EventStatus;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.ManagedCalendarEvent;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.TemplateStatus;
import com.cadence.repository.InterviewTemplateRepository;
import com.cadence.repository.ManagedCalendarEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US1: template CRUD round-trip, soft-retire, ids-only audit, the new indexes, and the cap-query status filter. */
class InterviewTemplateCrudIntegrationTest extends InterviewItBase {

    @Autowired ObjectMapper mapper;
    @Autowired InterviewTemplateRepository templates;
    @Autowired ManagedCalendarEventRepository managedEvents;

    private String createBody(String requiredId) {
        return "{\"name\":\"Phone Screen\",\"durationMinutes\":45,\"slotCadenceMinutes\":15,"
            + "\"bufferBeforeMinutes\":15,\"bufferAfterMinutes\":15,\"dailyCapPerInterviewer\":2,"
            + "\"requiredMemberIds\":[\"" + requiredId + "\"]}";
    }

    @Test
    void createReadEditRetire_roundTrips_andAuditsIdsOnly() throws Exception {
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        Member interviewer = member("int@x.com", Role.INTERVIEWER);

        String json = mvc.perform(post("/api/internal/interview-templates").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(createBody(interviewer.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.durationMinutes").value(45))
            .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(json).get("id").asText();

        // read-back via list and get
        mvc.perform(get("/api/internal/interview-templates").cookie(rec))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.templates.length()").value(1));
        mvc.perform(get("/api/internal/interview-templates/" + id).cookie(rec))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requiredMemberIds[0]").value(interviewer.getId()));

        // edit
        String edit = "{\"name\":\"Phone Screen\",\"durationMinutes\":60,\"slotCadenceMinutes\":15,"
            + "\"bufferBeforeMinutes\":0,\"bufferAfterMinutes\":0,\"dailyCapPerInterviewer\":2,"
            + "\"requiredMemberIds\":[\"" + interviewer.getId() + "\"]}";
        mvc.perform(put("/api/internal/interview-templates/" + id).cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.durationMinutes").value(60));

        // retire (soft)
        mvc.perform(post("/api/internal/interview-templates/" + id + "/retire").cookie(rec).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RETIRED"));

        InterviewTemplate persisted = templates.findById(id).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(TemplateStatus.RETIRED); // not hard-deleted
        assertThat(persisted.getDurationMinutes()).isEqualTo(60);

        // audit rows exist with ids only (no name)
        assertThat(auditCount(AuthEventType.INTERVIEW_TEMPLATE_CREATED)).isEqualTo(1);
        assertThat(auditCount(AuthEventType.INTERVIEW_TEMPLATE_UPDATED)).isEqualTo(1);
        assertThat(auditCount(AuthEventType.INTERVIEW_TEMPLATE_RETIRED)).isEqualTo(1);
        JsonNode auditDocs = mapper.readTree(mapper.writeValueAsString(
            mongoTemplate.findAll(org.bson.Document.class, "authAuditLog")));
        assertThat(auditDocs.toString()).doesNotContain("Phone Screen"); // name never audited
    }

    @Test
    void changeUnit008_createsBothIndexes() {
        assertThat(hasIndex("interviewTemplates", "workspaceId", "status")).isTrue();
        assertThat(hasIndex("managedCalendarEvents", "workspaceId", "memberId", "startAt")).isTrue();
    }

    @Test
    void capQuery_excludesDeletedAndCleanupIncompleteRows() {
        Instant from = Instant.parse("2026-06-15T00:00:00Z");
        Instant to = Instant.parse("2026-06-16T00:00:00Z");
        save("m1", EventStatus.CREATED, Instant.parse("2026-06-15T09:00:00Z"));
        save("m1", EventStatus.DELETED, Instant.parse("2026-06-15T10:00:00Z"));
        save("m1", EventStatus.CLEANUP_INCOMPLETE, Instant.parse("2026-06-15T11:00:00Z"));

        List<ManagedCalendarEvent> live = managedEvents
            .findLiveForCap(WS, "m1", List.of(EventStatus.DELETED, EventStatus.CLEANUP_INCOMPLETE), from, to);

        assertThat(live).hasSize(1); // only the CREATED row counts toward the cap
        assertThat(live.get(0).getStatus()).isEqualTo(EventStatus.CREATED);
    }

    private void save(String memberId, EventStatus status, Instant startAt) {
        ManagedCalendarEvent e = new ManagedCalendarEvent();
        e.setWorkspaceId(WS);
        e.setBookingRef("b-" + startAt);
        e.setMemberId(memberId);
        e.setProvider(com.cadence.domain.CalendarProvider.GOOGLE);
        e.setProviderEventId("ev-" + startAt);
        e.setStatus(status);
        e.setStartAt(startAt);
        e.setEndAt(startAt.plusSeconds(3600));
        managedEvents.save(e);
    }

    private long auditCount(AuthEventType type) {
        return mongoTemplate.count(new Query(Criteria.where("eventType").is(type.name())), "authAuditLog");
    }

    private boolean hasIndex(String collection, String... fields) {
        for (IndexInfo idx : mongoTemplate.indexOps(collection).getIndexInfo()) {
            List<String> keys = idx.getIndexFields().stream().map(f -> f.getKey()).toList();
            if (keys.containsAll(List.of(fields))) {
                return true;
            }
        }
        return false;
    }
}
