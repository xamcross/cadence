package com.cadence.calendar;

import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.Member;
import com.cadence.domain.MemberStatus;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** US3: disconnect, best-effort revoke, idempotency, and the deactivation/erasure seam (T037). */
class CalendarDisconnectIntegrationTest extends CalendarItBase {

    @Test
    void disconnect_deletesRow_revokes_audits() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        stubRevoke(CalendarProvider.GOOGLE);

        connectionService.disconnect(WS, m.getId(), CalendarProvider.GOOGLE);

        assertThat(connectionRepo.findByWorkspaceIdAndMemberIdAndProvider(WS, m.getId(), CalendarProvider.GOOGLE)).isEmpty();
        assertThat(wm.count(revokePath(CalendarProvider.GOOGLE), "")).isGreaterThanOrEqualTo(1); // QA #10
        assertThat(mongoTemplate.findAll(AuthAuditEvent.class))
            .filteredOn(a -> a.getEventType() == AuthEventType.CALENDAR_DISCONNECTED).hasSize(1);
    }

    @Test
    void disconnect_revokeFails_rowStillDeleted() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        stubRevokeFails(CalendarProvider.GOOGLE); // FR-006 best-effort

        connectionService.disconnect(WS, m.getId(), CalendarProvider.GOOGLE);

        assertThat(connectionRepo.findByWorkspaceIdAndMemberIdAndProvider(WS, m.getId(), CalendarProvider.GOOGLE)).isEmpty();
    }

    @Test
    void disconnect_absentConnection_isNoOp() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connectionService.disconnect(WS, m.getId(), CalendarProvider.GOOGLE); // no throw
        assertThat(mongoTemplate.findAll(CalendarConnection.class)).isEmpty();
    }

    @Test
    void disconnectAll_removesBothProviders_memberErasureSeam() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        connect(m, CalendarProvider.MICROSOFT, "alex@contoso.com");
        stubRevoke(CalendarProvider.GOOGLE);
        stubRevoke(CalendarProvider.MICROSOFT);

        connectionService.disconnectAll(WS, m.getId());

        assertThat(connectionRepo.findByWorkspaceIdAndMemberId(WS, m.getId())).isEmpty();
    }

    @Test
    void deactivation_deletesAllMemberConnections() {
        member("admin@x.com", Role.ADMIN); // keep an active admin so the recruiter deactivation is allowed
        Member m = member("rec@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        connect(m, CalendarProvider.MICROSOFT, "alex@contoso.com");
        stubRevoke(CalendarProvider.GOOGLE);
        stubRevoke(CalendarProvider.MICROSOFT);

        roleService.guardedDeactivate(WS, m.getId());

        Member reloaded = memberService.findById(m.getId());
        assertThat(reloaded.getStatus()).isEqualTo(MemberStatus.DEACTIVATED);
        assertThat(connectionRepo.findByWorkspaceIdAndMemberId(WS, m.getId())).isEmpty(); // FR-007
    }
}
