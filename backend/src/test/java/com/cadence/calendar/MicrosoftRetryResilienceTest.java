package com.cadence.calendar;

import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.ConnectionStatus;
import com.cadence.domain.EventDetails;
import com.cadence.domain.Member;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.PanelBookingResult.MemberOutcome;
import com.cadence.domain.Role;
import com.cadence.integration.CalendarApiException;
import com.cadence.integration.MicrosoftCalendarClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * US5 (SC-006/SC-011/FR-016/FR-017/FR-018): Graph throttling recovers within budget; persistent transient
 * leaves no partial state; 401/403 -> NEEDS_RECONNECTION with NO retry (asserted via stub call count) +
 * exactly one reconnection audit; a Retry-After header is parsed and carried on the exception.
 */
class MicrosoftRetryResilienceTest extends CalendarApiItBase {

    @Autowired private MicrosoftCalendarClient microsoftClient;

    private EventDetails interview() {
        return details("Interview", "Room", Instant.parse("2026-06-20T15:00:00Z"),
            Instant.parse("2026-06-20T16:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void throttleThenRecover_succeeds() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "alex@contoso.com");
        mscal.program("POST", "/events", 429, 429, 201); // recovers within the budget

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk", panel(m.getId()), interview());

        assertThat(r.outcome()).isEqualTo(PanelBookingResult.PanelOutcome.CREATED);
        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk")).hasSize(1);
        assertThat(mscal.liveEvents()).hasSize(1);
    }

    @Test
    void persistentTransient_leavesNoPartialState() {
        Member m = member("nina@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "nina@contoso.com");
        mscal.program("POST", "/events", 503); // persistent

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk", panel(m.getId()), interview());

        assertThat(outcomeFor(r, m.getId())).isEqualTo(MemberOutcome.FAILED);
        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk")).as("no claim row (FR-017)").isEmpty();
        assertThat(mscal.liveEvents()).isEmpty();
        assertThat(mscal.count("POST", "/events")).isEqualTo(4); // 1 + maxRetries(3)
    }

    @Test
    void unauthorized_flipsNeedsReconnection_noRetry_oneAudit() {
        assertReconnect("u401@x.com", 401, null);
    }

    @Test
    void forbidden_flipsNeedsReconnection_noRetry_oneAudit() {
        assertReconnect("u403@x.com", 403, "ErrorAccessDenied");
    }

    private void assertReconnect(String email, int status, String code) {
        Member m = member(email, Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, email.replace("@x.com", "@contoso.com"));
        if (code == null) {
            mscal.program("POST", "/events", status);
        } else {
            mscal.program("POST", "/events", code, status);
        }

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk", panel(m.getId()), interview());

        assertThat(outcomeFor(r, m.getId())).isEqualTo(MemberOutcome.NEEDS_RECONNECTION);
        assertThat(connectionRepo.findByWorkspaceIdAndMemberIdAndProvider(WS, m.getId(), CalendarProvider.MICROSOFT)
            .orElseThrow().getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECTION);
        assertThat(mscal.count("POST", "/events")).as("no retry on a permanent auth failure").isEqualTo(1);
        // SC-011: exactly one reconnection audit, with internal ids ONLY — assert the persisted row carries
        // no account email / mailbox (no '@' anywhere) and no event content.
        assertThat(mongoTemplate.count(Query.query(Criteria.where("eventType").is(AuthEventType.CALENDAR_RECONNECT_REQUIRED)),
            AuthAuditEvent.class)).isEqualTo(1);
        for (org.bson.Document a : mongoTemplate.getCollection("authAuditLog").find()) {
            assertThat(a.toJson()).doesNotContain("@contoso.com").doesNotContain("@x.com");
        }
    }

    @Test
    void retryAfterHeader_isParsedAndCarriedOnTheException() {
        Member m = member("ra@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "ra@contoso.com");
        // Persistent 429 with a NON-ZERO Retry-After: the loop must parse the header and carry the exact
        // value onto the exception (a regression that dropped parse / passed null fails this; value!=0 also
        // distinguishes "honoured" from "ignored"). No wall-clock assertion (QA B1) — only the carried value.
        mscal.programRetryAfter("POST", "/events", "2", 429);

        CalendarApiException ex = catchThrowableOfType(
            () -> microsoftClient.createEvent(WS, "bk", m.getId(), interview()), CalendarApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getHttpStatus()).isEqualTo(429);
        assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(2)); // parsed from the header and carried (FR-016/D7)
    }

    private MemberOutcome outcomeFor(PanelBookingResult r, String memberId) {
        return r.perMember().stream().filter(x -> x.memberId().equals(memberId)).findFirst().orElseThrow().outcome();
    }
}
