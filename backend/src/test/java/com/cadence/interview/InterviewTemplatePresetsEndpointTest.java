package com.cadence.interview;

import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The preset gallery read: static catalogue, role-gated, literal route not shadowed by GET /{id}. */
class InterviewTemplatePresetsEndpointTest extends InterviewItBase {

    @Test
    void recruiter_getsAllSixPresets_withStructuralValues() throws Exception {
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        mvc.perform(get("/api/internal/interview-templates/presets").cookie(rec))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.presets.length()").value(6))
            .andExpect(jsonPath("$.presets[0].key").value("PHONE_SCREEN"))
            .andExpect(jsonPath("$.presets[0].durationMinutes").value(30))
            .andExpect(jsonPath("$.presets[3].key").value("PANEL_LOOP"))
            .andExpect(jsonPath("$.presets[3].poolN").value(2))
            .andExpect(jsonPath("$.presets[2].optionalShadow").value(true))
            .andExpect(jsonPath("$.presets[2].starterEmailTypes[2]").value("REMINDER_24H"));
    }

    @Test
    void literalPresetsRoute_doesNotShadowGetById() throws Exception {
        Cookie rec = cookie(member("rec2@x.com", Role.RECRUITER));
        mvc.perform(get("/api/internal/interview-templates/000000000000000000000000").cookie(rec))
            .andExpect(status().isNotFound());
    }
}
