package com.cadence.calendar;

import com.cadence.auth.AuthTestConfig;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.ConnectionStatus;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.integration.CalendarProviderTransientException;
import com.cadence.integration.CalendarReconnectRequiredException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** US4: revoked-grant and transient-failure handling (T043). */
class CalendarReconnectIntegrationTest extends CalendarItBase {

    private void expireAccess() {
        clock.set(AuthTestConfig.FIXED_START.plusSeconds(3600));
    }

    @Test
    void invalidGrant_flipsToNeedsReconnection_nullsAccess_audits_noRetryLoop() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        expireAccess();
        stubRefreshError(CalendarProvider.GOOGLE, 400, "invalid_grant");

        assertThatThrownBy(() -> tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE))
            .isInstanceOf(CalendarReconnectRequiredException.class);

        CalendarConnection after = connectionRepo
            .findByWorkspaceIdAndMemberIdAndProvider(WS, m.getId(), CalendarProvider.GOOGLE).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECTION);
        assertThat(after.getAccessToken()).isNull();          // Security #7
        assertThat(after.getRefreshToken()).isEqualTo("refresh-google"); // retained
        assertThat(mongoTemplate.findAll(AuthAuditEvent.class))
            .filteredOn(a -> a.getEventType() == AuthEventType.CALENDAR_RECONNECT_REQUIRED).hasSize(1);
    }

    @Test
    void transientFailure_staysConnected_rowByteIdentical() {
        Member m = member("alex@x.com", Role.RECRUITER);
        CalendarConnection before = connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        expireAccess();
        stubRefreshError(CalendarProvider.GOOGLE, 503, null); // transient

        assertThatThrownBy(() -> tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE))
            .isInstanceOf(CalendarProviderTransientException.class);

        CalendarConnection after = connectionRepo.findById(before.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ConnectionStatus.CONNECTED); // FR-016
        assertThat(after.getTokenVersion()).isEqualTo(before.getTokenVersion()); // no write
        assertThat(after.getRefreshToken()).isEqualTo("refresh-google");
        // Bounded retry (FR-016): exactly initial + maxRefreshRetries(3) attempts = 4, not infinite.
        assertThat(wm.count(tokenPath(CalendarProvider.GOOGLE), "grant_type=refresh_token")).isEqualTo(4);
    }

    @Test
    void fatalFailure_staysConnected_noRetry_noStateChange() {
        Member m = member("alex@x.com", Role.RECRUITER);
        CalendarConnection before = connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        expireAccess();
        stubRefreshError(CalendarProvider.GOOGLE, 401, "invalid_client"); // FATAL (config error), not invalid_grant

        assertThatThrownBy(() -> tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE))
            .isInstanceOf(CalendarProviderTransientException.class);

        CalendarConnection after = connectionRepo.findById(before.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ConnectionStatus.CONNECTED); // not flipped to NEEDS_RECONNECTION
        assertThat(after.getTokenVersion()).isEqualTo(before.getTokenVersion()); // no write
        // FATAL is not retried (unlike transient): exactly one provider POST.
        assertThat(wm.count(tokenPath(CalendarProvider.GOOGLE), "grant_type=refresh_token")).isEqualTo(1);
    }

    @Test
    void needsReconnection_isVisibleInList() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        expireAccess();
        stubRefreshError(CalendarProvider.GOOGLE, 400, "invalid_grant");
        try {
            tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE);
        } catch (CalendarReconnectRequiredException ignored) {
            // expected
        }
        assertThat(connectionService.list(WS, m.getId()))
            .anySatisfy(c -> assertThat(c.getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECTION));
    }
}
