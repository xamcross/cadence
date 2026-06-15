package com.cadence.gdpr;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cadence.domain.Candidate;
import com.cadence.domain.ErasureReasonCode;
import com.cadence.domain.ErasureRequest;
import com.cadence.service.ContactPermissionGate;
import com.cadence.service.ErasureRequestService;
import com.cadence.service.RetentionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** T046 / US6 / SC-010: across every GDPR flow at TRACE, no candidate PII sentinel reaches the logs. */
class GdprLogPiiScanTest extends GdprItBase {

    private static final String NAME = "ZZSENTINELNAME_DONOTLOG";
    private static final String EMAIL = "sentinel@dont.log";
    private static final String PHONE = "+15550101010";

    @Autowired ContactPermissionGate gate;
    @Autowired ErasureRequestService requestService;
    @Autowired RetentionService retention;

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level original;

    @AfterEach
    void restore() {
        root.detachAppender(appender);
        if (original != null) {
            root.setLevel(original);
        }
    }

    @Test
    void noCandidatePiiInLogs_acrossEveryFlow_atTrace() throws Exception {
        Cookie admin = adminCookie();

        original = root.getLevel();
        root.setLevel(Level.TRACE);
        appender.start();
        root.addAppender(appender);

        // create -> basis -> gate -> audit-read (decrypt path) -> request/confirm -> retention -> validation error
        Candidate c = seedCandidate(NAME, EMAIL, PHONE);
        put("/api/internal/candidates/{id}/basis", c.getId());
        mvc.perform(put("/api/internal/candidates/{id}/basis", c.getId()).cookie(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"lawfulBasis\":\"CONSENT\"}"));
        gate.evaluate(WS, c.getId());
        mongoTemplate.findById(c.getId(), Candidate.class); // force a decrypt into an entity
        mvc.perform(get("/api/internal/candidates/{id}/audit", c.getId()).cookie(admin));
        ErasureRequest r = requestService.requestErasure(WS, c.getId(), ErasureReasonCode.CANDIDATE_REQUEST);
        mvc.perform(post("/api/internal/erasure-requests/{id}/confirm", r.getId()).cookie(admin).with(csrf()));
        retention.scan(WS);
        // forced validation error
        mvc.perform(put("/api/internal/candidates/{id}/basis", c.getId()).cookie(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"lawfulBasis\":\"BOGUS\"}"));

        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : appender.list) {
            sb.append(e.getFormattedMessage()).append('\n');
            if (e.getArgumentArray() != null) {
                sb.append(Arrays.toString(e.getArgumentArray())).append('\n');
            }
            if (e.getMDCPropertyMap() != null) {
                sb.append(e.getMDCPropertyMap()).append('\n');
            }
            if (e.getThrowableProxy() != null) {
                sb.append(e.getThrowableProxy().getMessage()).append('\n');
            }
        }
        String logs = sb.toString();

        // Vacuity guard: the TRACE run actually produced logs.
        assertThat(appender.list).isNotEmpty();
        // No candidate PII sentinel anywhere.
        assertThat(logs).doesNotContain(NAME).doesNotContain(EMAIL).doesNotContain(PHONE);
    }
}
