package com.cadence.emailtemplate;

import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SC-004: the D4 malformed-token truth table + empty/over-cap fields -> value-free 400, 0 persisted. */
class EmailTemplateValidationTest extends EmailTemplateItBase {

    private long rawCount() {
        return mongoTemplate.getCollection("emailTemplates").countDocuments();
    }

    private void expectInvalid(Cookie c, String body) throws Exception {
        mvc.perform(put("/api/internal/email-templates/INVITATION").cookie(c).with(csrf())
                .contentType("application/json").content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_template"))
            .andExpect(jsonPath("$.fields").isMap());
        assertThat(rawCount()).as("nothing persisted on a rejected save").isZero();
    }

    private static String body(String subject, String bodyText) {
        return "{\"stageKey\":\"BASE\",\"subject\":\"" + subject + "\",\"body\":\"" + bodyText + "\"}";
    }

    @Test
    void rejectsEmptyAndUnknownAndDisallowedAndMalformedTokens() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        expectInvalid(admin, body("", "Hi {{candidate_name}}"));                 // empty subject
        expectInvalid(admin, body("S {{workspace_name}}", ""));                  // empty body
        expectInvalid(admin, body("S", "Hi {{not_a_token}}"));                   // unknown token
        expectInvalid(admin, body("S", "Loc {{location}}"));                     // location not permitted for INVITATION
        expectInvalid(admin, body("S", "Hi {{}}"));                              // empty/malformed token
        expectInvalid(admin, body("S", "Hi {{ candidate_name }}"));             // whitespace-padded -> malformed
    }

    @Test
    void rejectsOverLengthSubjectAndBody() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        expectInvalid(admin, body("x".repeat(201), "Hi {{candidate_name}}"));   // subject > max
        expectInvalid(admin, body("S {{workspace_name}}", "x".repeat(10001)));  // body > max
    }

    @Test
    void rejectsOverTokenCount() throws Exception {
        // FR-022 render-amplification bound: > max-tokens-per-template (50) token occurrences -> 400.
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        String manyTokens = "{{candidate_name}}".repeat(51);
        expectInvalid(admin, body("S {{workspace_name}}", manyTokens));
    }

    @Test
    void acceptsSingleClosingBrace_asLiteralText() throws Exception {
        // {{candidate_name} (single closing brace) is NOT a token -> accepted as inert literal text (truth table).
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(put("/api/internal/email-templates/INVITATION").cookie(admin).with(csrf())
                .contentType("application/json").content(body("S {{workspace_name}}", "Hi {{candidate_name}")))
            .andExpect(status().isOk());
        assertThat(rawCount()).isEqualTo(1);
    }
}
