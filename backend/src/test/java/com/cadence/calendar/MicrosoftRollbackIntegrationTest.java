package com.cadence.calendar;

import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventDetails;
import com.cadence.domain.EventStatus;
import com.cadence.domain.Member;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.PanelBookingResult.MemberOutcome;
import com.cadence.domain.PanelBookingResult.PanelOutcome;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 (SC-007/FR-019): a Microsoft partial-create panel rolls back to ZERO orphans (asserted via the stub's
 * residual store); a compensating delete that itself fails -> CLEANUP_INCOMPLETE + audit + orphan remains.
 */
class MicrosoftRollbackIntegrationTest extends CalendarApiItBase {

    private EventDetails interview() {
        return details("Interview", "Room", Instant.parse("2026-06-20T15:00:00Z"),
            Instant.parse("2026-06-20T16:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void partialCreate_rollsBackToZeroOrphans() {
        Member a = member("a@x.com", Role.RECRUITER);
        Member b = member("b@x.com", Role.RECRUITER);
        connect(a, CalendarProvider.MICROSOFT, "a@contoso.com");
        connect(b, CalendarProvider.MICROSOFT, "b@contoso.com");
        // A insert 201; B insert 503 x (1 + maxRetries=3) -> B fails -> roll back A.
        mscal.program("POST", "/events", 201, 503, 503, 503, 503);

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk", panel(a.getId(), b.getId()), interview());

        assertThat(r.outcome()).isEqualTo(PanelOutcome.ROLLED_BACK);
        assertThat(outcomeFor(r, a.getId())).isEqualTo(MemberOutcome.ROLLED_BACK);
        assertThat(outcomeFor(r, b.getId())).isEqualTo(MemberOutcome.FAILED);
        assertThat(mscal.liveEvents()).as("zero orphans after rollback").isEmpty();
        assertThat(managedEvents.findByWorkspaceIdAndBookingRefAndMemberIdAndProvider(WS, "bk", a.getId(), CalendarProvider.MICROSOFT)
            .orElseThrow().getStatus()).isEqualTo(EventStatus.DELETED);
        assertThat(managedEvents.findByWorkspaceIdAndBookingRefAndMemberIdAndProvider(WS, "bk", b.getId(), CalendarProvider.MICROSOFT))
            .isEmpty(); // B's claim never written (provider-first; FR-014)
    }

    @Test
    void compensatingDeleteFails_isCleanupIncomplete_orphanRemains() {
        Member a = member("a@x.com", Role.RECRUITER);
        Member b = member("b@x.com", Role.RECRUITER);
        connect(a, CalendarProvider.MICROSOFT, "a@contoso.com");
        connect(b, CalendarProvider.MICROSOFT, "b@contoso.com");
        mscal.program("POST", "/events", 201, 503, 503, 503, 503); // A ok, B fails
        mscal.program("DELETE", "/events", 503);                    // A's compensating delete persistently fails

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk2", panel(a.getId(), b.getId()), interview());

        assertThat(r.outcome()).isEqualTo(PanelOutcome.CLEANUP_INCOMPLETE);
        assertThat(outcomeFor(r, a.getId())).isEqualTo(MemberOutcome.CLEANUP_INCOMPLETE);
        String orphanId = managedEvents
            .findByWorkspaceIdAndBookingRefAndMemberIdAndProvider(WS, "bk2", a.getId(), CalendarProvider.MICROSOFT)
            .orElseThrow().getProviderEventId();
        assertThat(managedEvents.findByWorkspaceIdAndBookingRefAndMemberIdAndProvider(WS, "bk2", a.getId(), CalendarProvider.MICROSOFT)
            .orElseThrow().getStatus()).isEqualTo(EventStatus.CLEANUP_INCOMPLETE);
        assertThat(mongoTemplate.count(Query.query(Criteria.where("eventType").is(AuthEventType.CALENDAR_EVENT_CLEANUP_INCOMPLETE)),
            AuthAuditEvent.class)).isGreaterThanOrEqualTo(1);
        assertThat(mscal.liveEvents()).as("orphan still present, reconcilable").contains(orphanId);
    }

    private MemberOutcome outcomeFor(PanelBookingResult r, String memberId) {
        return r.perMember().stream().filter(x -> x.memberId().equals(memberId)).findFirst().orElseThrow().outcome();
    }
}
