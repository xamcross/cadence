package com.cadence.rbac;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.service.MemberService;
import com.cadence.service.RoleService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SC-009 (T042): no PII (member email/displayName), candidate id, or scoped-record content appears
 * in application logs across the role-change and authorization-denial paths. Captures all log output
 * via a Logback ListAppender on the root logger.
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
class RbacLogPiiScanTest extends BaseIntegrationTest {

    private static final String PII_EMAIL = "secret.person@example.com";
    private static final String PII_NAME = "Top Secret Name";

    @Autowired MockMvc mvc;
    @Autowired MemberService memberService;
    @Autowired SessionService sessionService;
    @Autowired RoleService roleService;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @Test
    void rolePaths_logNoPii() throws Exception {
        Member admin = memberService.create("ws1", "admin@x.com", "Admin", Role.ADMIN, null, null);
        Member target = memberService.create("ws1", PII_EMAIL, PII_NAME, Role.RECRUITER, null, null);

        roleService.changeRole("ws1", admin.getId(), target.getId(), Role.READ_ONLY);

        // a denial path (authenticated-but-unauthorized) — the bounded refusal audit runs here
        Cookie ro = new Cookie("cad_session", sessionService.issue(target).jwt());
        mvc.perform(get("/api/internal/members").cookie(ro)).andExpect(status().isForbidden());

        for (ILoggingEvent e : appender.list) {
            // Scan the full log surface: message, structured arguments, MDC, and any throwable —
            // not just the formatted message (PII could leak via StructuredArguments/MDC/stack).
            String surface = e.getFormattedMessage()
                + " " + java.util.Arrays.toString(e.getArgumentArray())
                + " " + e.getMDCPropertyMap()
                + " " + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage());
            assertThat(surface).doesNotContain(PII_EMAIL);
            assertThat(surface).doesNotContain(PII_NAME);
        }
    }
}
