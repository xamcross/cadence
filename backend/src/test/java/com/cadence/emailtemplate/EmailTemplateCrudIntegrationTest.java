package com.cadence.emailtemplate;

import com.cadence.api.EmailTemplateDtos.EditRequest;
import com.cadence.api.EmailTemplateExceptions;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.service.EmailTemplateService;
import jakarta.servlet.http.Cookie;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SC-001/006: built-in defaults, override persistence + zero rows for un-edited types, tone, reset, concurrency. */
class EmailTemplateCrudIntegrationTest extends EmailTemplateItBase {

    @Autowired EmailTemplateService service;

    private long rawCount() {
        return mongoTemplate.getCollection("emailTemplates").countDocuments();
    }

    private static String edit(String subject, String body, Long ver) {
        return "{\"stageKey\":\"BASE\",\"subject\":\"" + subject + "\",\"body\":\"" + body + "\""
            + (ver == null ? "" : ",\"expectedVersion\":" + ver) + "}";
    }

    @Test
    void list_returnsBuiltInDefaultsForEveryType() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(get("/api/internal/email-templates").cookie(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.templates.length()").value(EmailMessageType.values().length))
            .andExpect(jsonPath("$.templates[0].source").value("BUILTIN"))
            .andExpect(jsonPath("$.templates[0].subject").isNotEmpty())
            .andExpect(jsonPath("$.templates[0].body").isNotEmpty());
        assertThat(rawCount()).isZero(); // no rows materialised by reads
    }

    @Test
    void edit_persistsOverride_defaultUntouched_versionStartsAtZero() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(put("/api/internal/email-templates/INVITATION").cookie(admin).with(csrf())
                .contentType("application/json").content(edit("Hello {{workspace_name}}", "Hi {{candidate_name}}", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("OVERRIDE"))
            .andExpect(jsonPath("$.version").value(0));
        // read back
        mvc.perform(get("/api/internal/email-templates/INVITATION").cookie(admin))
            .andExpect(jsonPath("$.subject").value("Hello {{workspace_name}}"))
            .andExpect(jsonPath("$.source").value("OVERRIDE"));
        // a different, un-edited type still renders the built-in default with ZERO persisted rows for it
        mvc.perform(get("/api/internal/email-templates/CONFIRMATION").cookie(admin))
            .andExpect(jsonPath("$.source").value("BUILTIN"));
        assertThat(rawCount()).isEqualTo(1); // only INVITATION/BASE persisted
    }

    @Test
    void applyTone_replacesWording() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(post("/api/internal/email-templates/INVITATION/apply-tone").cookie(admin).with(csrf())
                .contentType("application/json").content("{\"stageKey\":\"BASE\",\"tone\":\"FORMAL\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("OVERRIDE"))
            .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.startsWith("Dear {{candidate_name}},")));
    }

    @Test
    void reset_fallsBackToBuiltin_andIsIdempotent() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(put("/api/internal/email-templates/REJECTION").cookie(admin).with(csrf())
                .contentType("application/json").content(edit("S {{workspace_name}}", "Bye {{candidate_name}}", null)))
            .andExpect(status().isOk());
        assertThat(rawCount()).isEqualTo(1);
        // reset deletes the override -> built-in
        mvc.perform(post("/api/internal/email-templates/REJECTION/reset").cookie(admin).with(csrf())
                .contentType("application/json").content("{\"stageKey\":\"BASE\",\"expectedVersion\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("BUILTIN"));
        assertThat(rawCount()).isZero();
        // resetting an already-un-overridden type is a no-op 200
        mvc.perform(post("/api/internal/email-templates/REJECTION/reset").cookie(admin).with(csrf())
                .contentType("application/json").content("{\"stageKey\":\"BASE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("BUILTIN"));
    }

    @Test
    void storedDoc_isPlaintextAuthoringContent_noEncryptionMangling() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(put("/api/internal/email-templates/INVITATION").cookie(admin).with(csrf())
                .contentType("application/json").content(edit("Plain {{workspace_name}}", "Body {{candidate_name}}", null)))
            .andExpect(status().isOk());
        Document raw = mongoTemplate.getCollection("emailTemplates").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("subject")).isEqualTo("Plain {{workspace_name}}"); // un-encrypted by design
    }

    @Test
    void variantResolutionAndFallback() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        seedStage(WS, "stage1");
        seedStage(WS, "stage2");

        // create a CONFIRMATION variant for stage1
        mvc.perform(put("/api/internal/email-templates/CONFIRMATION").cookie(admin).with(csrf())
                .contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"subject\":\"Variant {{workspace_name}}\",\"body\":\"V {{candidate_name}}\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("OVERRIDE"));

        // rendering for stage1 uses the variant; for stage2 (no variant) and BASE it falls back to built-in
        mvc.perform(get("/api/internal/email-templates/CONFIRMATION?stageKey=stage1").cookie(admin))
            .andExpect(jsonPath("$.subject").value("Variant {{workspace_name}}"))
            .andExpect(jsonPath("$.source").value("OVERRIDE"));
        mvc.perform(get("/api/internal/email-templates/CONFIRMATION?stageKey=stage2").cookie(admin))
            .andExpect(jsonPath("$.source").value("BUILTIN"));
        mvc.perform(get("/api/internal/email-templates/CONFIRMATION?stageKey=BASE").cookie(admin))
            .andExpect(jsonPath("$.source").value("BUILTIN"));

        // reset the variant -> stage1 falls back to base/built-in
        mvc.perform(post("/api/internal/email-templates/CONFIRMATION/reset").cookie(admin).with(csrf())
                .contentType("application/json").content("{\"stageKey\":\"stage1\",\"expectedVersion\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("BUILTIN"));
        mvc.perform(get("/api/internal/email-templates/CONFIRMATION?stageKey=stage1").cookie(admin))
            .andExpect(jsonPath("$.source").value("BUILTIN"));
        assertThat(rawCount()).isZero();
    }

    @Test
    void concurrentFirstEdit_exactlyOneWins_otherIs409_oneRow() throws Exception {
        Member admin = member("admin@x.com", Role.ADMIN);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> task = () -> {
            start.await();
            try {
                return service.edit(WS, admin.getId(), Role.ADMIN, EmailMessageType.INVITATION,
                    new EditRequest("BASE", "S {{workspace_name}}", "B {{candidate_name}}", null));
            } catch (RuntimeException e) {
                return e;
            }
        };
        Future<Object> f1 = pool.submit(task);
        Future<Object> f2 = pool.submit(task);
        start.countDown();
        Object r1 = f1.get();
        Object r2 = f2.get();
        pool.shutdown();

        long successes = java.util.stream.Stream.of(r1, r2).filter(o -> !(o instanceof Exception)).count();
        long stale = java.util.stream.Stream.of(r1, r2)
            .filter(o -> o instanceof EmailTemplateExceptions.StaleTemplateException).count();
        assertThat(successes).isEqualTo(1);
        assertThat(stale).isEqualTo(1);
        assertThat(rawCount()).isEqualTo(1);
    }
}
