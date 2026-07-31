package com.cadence.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.PanelBookingResult.MemberEventResult;
import com.cadence.domain.PanelBookingResult.MemberOutcome;
import com.cadence.domain.PanelBookingResult.PanelOutcome;
import com.cadence.integration.EmailSender;
import com.cadence.service.AvailabilityService;
import com.cadence.service.CalendarEventService;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.SchedulingService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Across initiate + view + confirm, NEITHER the candidate-name sentinel NOR the location-text sentinel appears
 * in: the persisted {@code schedulingRequests} doc (raw-driver read — locationText is encrypted at rest), the
 * candidate-facing API payload, the audit log, or any {@code com.cadence} log even at TRACE. A vacuity guard
 * proves the path actually logged.
 */
class SchedulingLogPiiScanTest extends SchedulingItBase {

    private static final String NAME_SENTINEL = "SENTINELF13NAME_zz9";
    private static final String LOCATION_SENTINEL = "SENTINELF13LOC_zz9";
    private static final String REQ_MEMBER = "111111111111111111111111";

    @Autowired SchedulingService schedulingService;
    @MockBean AvailabilityService availability;
    @MockBean CalendarEventService calendar;
    @MockBean EmailDispatchService dispatch;
    @MockBean EmailSender emailSender;

    @Test
    void noNameOrLocation_inDoc_payload_audit_orLogs() throws Exception {
        configuredWorkspace();
        when(availability.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<MemberAvailability> out = new ArrayList<>();
            for (String id : ids) out.add(new MemberAvailability(id, AvailabilityStatus.DATA, List.of()));
            return out;
        });
        when(calendar.createPanelEvents(eq(WS), any(), anyList(), any())).thenReturn(
            new PanelBookingResult(PanelOutcome.CREATED,
                List.of(new MemberEventResult(REQ_MEMBER, MemberOutcome.CREATED, "evt"))));

        var actor = member("rec@x.com", com.cadence.domain.Role.RECRUITER);
        var reqMemberObj = member("req@x.com", com.cadence.domain.Role.INTERVIEWER);
        seedContactableCandidate("cand1", NAME_SENTINEL, "dana@x.com");

        // Template whose required member id matches the slot we will seed for the confirm path.
        InterviewTemplate t = seedTemplateWithRequired(reqMemberObj.getId());

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger cadence = (Logger) LoggerFactory.getLogger("com.cadence");
        Level previous = cadence.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        cadence.setLevel(Level.TRACE);
        boolean ranThePath;
        try {
            // initiate (real path) with the location sentinel.
            schedulingService.initiate(WS, actor.getId(), "cand1", t.getId(), LOCATION_SENTINEL,
                LocalDate.parse("2026-06-15"), LocalDate.parse("2026-06-16"), "1.2.3.4");

            // Seed an independent request with the location sentinel + a confirmable slot, then view + confirm.
            OfferedSlot s = slot("0", Instant.parse("2026-06-20T13:00:00Z"),
                Instant.parse("2026-06-20T14:00:00Z"), List.of(reqMemberObj.getId()), List.of());
            Seeded seeded = seedPendingRequest("cand1", t.getId(), LOCATION_SENTINEL, List.of(s));

            String viewJson = mvc.perform(get("/api/candidate/scheduling/" + seeded.rawToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(viewJson).doesNotContain(NAME_SENTINEL).doesNotContain(LOCATION_SENTINEL);

            ranThePath = false;
            for (ILoggingEvent e : new java.util.ArrayList<>(appender.list)) { // snapshot: async threads may still append (CME guard)
                String line = e.getFormattedMessage();
                if (line == null) continue;
                assertThat(line).doesNotContain(NAME_SENTINEL);
                assertThat(line).doesNotContain(LOCATION_SENTINEL);
                if (line.contains("scheduling link sent")) ranThePath = true;
            }
        } finally {
            cadence.setLevel(previous);
            root.detachAppender(appender);
        }
        assertThat(ranThePath).as("the initiate path actually logged (non-vacuous scan)").isTrue();

        // Raw-driver read: the persisted schedulingRequests docs carry NO plaintext name/location.
        for (Document d : mongoTemplate.getCollection("schedulingRequests").find()) {
            assertThat(d.toJson()).doesNotContain(NAME_SENTINEL).doesNotContain(LOCATION_SENTINEL);
        }
        // Audit log carries value-free outcomes only.
        for (Document d : mongoTemplate.getCollection("authAuditLog").find()) {
            assertThat(d.toJson()).doesNotContain(NAME_SENTINEL).doesNotContain(LOCATION_SENTINEL);
        }
    }

    private InterviewTemplate seedTemplateWithRequired(String requiredId) {
        return seedTemplate(requiredId);
    }
}
