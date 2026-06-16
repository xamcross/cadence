package com.cadence.emailtemplate;

import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SC-004/FR-020: the per-type variant cap is enforced (cap lowered to 1 for a cheap assertion). */
@TestPropertySource(properties = "email.template.max-variants-per-type=1")
class EmailTemplateVariantCapTest extends EmailTemplateItBase {

    private static String variant(String stageKey) {
        return "{\"stageKey\":\"" + stageKey + "\",\"subject\":\"S {{workspace_name}}\",\"body\":\"V {{candidate_name}}\"}";
    }

    @Test
    void rejectsVariantBeyondCap() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        seedStage(WS, "stage1");
        seedStage(WS, "stage2");
        // first variant is allowed (count 0 < cap 1)
        mvc.perform(put("/api/internal/email-templates/CONFIRMATION").cookie(admin).with(csrf())
                .contentType("application/json").content(variant("stage1")))
            .andExpect(status().isOk());
        // second variant exceeds the cap (count 1 >= 1) -> 400 invalid_template
        mvc.perform(put("/api/internal/email-templates/CONFIRMATION").cookie(admin).with(csrf())
                .contentType("application/json").content(variant("stage2")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_template"));
    }
}
