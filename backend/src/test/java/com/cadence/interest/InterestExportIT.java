package com.cadence.interest;

import com.cadence.domain.InterestRequest;
import com.cadence.domain.InterestRequestStatus;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F70 SC-012 / FR-010 EXPORT half: the CSV export of the admin review queue neutralizes every free-text cell at the
 * export boundary via {@code CsvInjectionEscaper} (mirroring the F50 dashboard export). Asserts: (a) a formula
 * payload in name/message is neutralized (the dangerous cell does NOT start with a bare {@code = + - @}); (b) the
 * endpoint is Admin-only (non-admin -> 403, never a swallowed 500); (c) {@code text/csv} + attachment +
 * {@code no-store}; (d) workspace-scoped (an other-workspace row never appears).
 */
class InterestExportIT extends InterestItBase {

    private String seed(String workspaceId, String name, String email, String organization, String message,
                        InterestRequestStatus statusValue) {
        InterestRequest r = new InterestRequest();
        r.setWorkspaceId(workspaceId);
        r.setName(name);
        r.setEmail(email);
        r.setEmailHash(crypto.emailHash(email + "|" + statusValue));
        if (statusValue == InterestRequestStatus.NEW || statusValue == InterestRequestStatus.REVIEWED) {
            r.setOpenEmailHash(crypto.emailHash(email + "|open|" + statusValue));
        }
        r.setOrganization(organization);
        r.setMessage(message);
        r.setStatus(statusValue);
        r.setSubmittedAt(Instant.now(clock));
        r.setUpdatedAt(Instant.now(clock));
        return mongoTemplate.save(r).getId();
    }

    private String exportBody(Cookie admin, String statusFilter) throws Exception {
        MvcResult res = mvc.perform(get("/api/internal/interest-requests/export")
                .param("status", statusFilter).cookie(admin))
            .andExpect(status().isOk())
            .andReturn();
        return res.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void formulaPayload_isNeutralizedInExport() throws Exception {
        // Dangerous free-text in name + message + organization (the CSV-injection vectors).
        seed(WS, "=cmd|' /C calc'!A0", "plus@example.com", "+SUM(1+1)",
            "@foo bar", InterestRequestStatus.NEW);
        seed(WS, "-2+3+cmd", "minus@example.com", "Acme", "ok",
            InterestRequestStatus.NEW);
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));

        String csv = exportBody(admin, "open");

        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("name,email,organization,message,status,submittedAt");
        // Every data line: the FIRST character of each cell must not be a bare formula trigger (CsvInjectionEscaper
        // prefixes a dangerous value with a single quote, optionally then RFC-4180-quotes it).
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            // The escaper prefixes the dangerous value with ' (so the spreadsheet treats it as literal text). The
            // payloads contain no comma/quote so they are NOT additionally RFC-4180-wrapped; the cell starts with '.
            for (String cell : line.split(",", -1)) {
                if (cell.isEmpty()) {
                    continue;
                }
                char first = cell.charAt(0);
                assertThat(first)
                    .as("cell must not begin with a bare formula trigger: <%s>", cell)
                    .isNotIn('=', '+', '-', '@', '|');
            }
        }
        // Specifically: the =cmd payload survives as data but is neutralized (prefixed with a quote).
        assertThat(csv).contains("'=cmd|' /C calc'!A0".substring(0, 5)); // starts with '=cmd
        assertThat(csv).contains("'+SUM(1+1)");
        assertThat(csv).contains("'@foo bar");
        assertThat(csv).contains("'-2+3+cmd");
        // And no exported line begins (right after a delimiter) with a live formula.
        assertThat(csv).doesNotContain(",=cmd");
        assertThat(csv).doesNotContain("\n=cmd");
    }

    @Test
    void export_isAdminOnly_othersForbiddenNot500() throws Exception {
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));
        mvc.perform(get("/api/internal/interest-requests/export").cookie(admin))
            .andExpect(status().isOk());
        for (Role denied : new Role[]{Role.RECRUITER, Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY}) {
            Cookie c = cookie(member(denied.name().toLowerCase() + "@example.com", denied));
            mvc.perform(get("/api/internal/interest-requests/export").cookie(c))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void export_setsCsvContentTypeAndAttachmentAndNoStore() throws Exception {
        seed(WS, "Dana", "dana@example.com", "Acme", "hello", InterestRequestStatus.NEW);
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));
        mvc.perform(get("/api/internal/interest-requests/export").param("status", "open").cookie(admin))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(header().string("Content-Disposition",
                containsString("attachment; filename=\"interest-requests.csv\"")))
            .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void export_isWorkspaceScoped() throws Exception {
        seed(WS, "MineOnly", "mine@example.com", "Acme", "mine", InterestRequestStatus.NEW);
        seed("other-ws", "OtherWorkspace", "other@example.com", "Other", "other", InterestRequestStatus.NEW);
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));

        String csv = exportBody(admin, "open");

        assertThat(csv).contains("MineOnly");
        assertThat(csv).doesNotContain("OtherWorkspace");
    }

    @Test
    void export_defaultFilterExcludesReviewed() throws Exception {
        seed(WS, "NewOne", "new@example.com", null, null, InterestRequestStatus.NEW);
        seed(WS, "ReviewedOne", "reviewed@example.com", null, null, InterestRequestStatus.REVIEWED);
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));

        String openCsv = exportBody(admin, "open");
        assertThat(openCsv).contains("NewOne");
        assertThat(openCsv).doesNotContain("ReviewedOne");

        String allCsv = exportBody(admin, "all");
        assertThat(allCsv).contains("NewOne");
        assertThat(allCsv).contains("ReviewedOne");
    }
}
