package com.cadence.calendar;

import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.ConnectionStatus;
import com.cadence.domain.Member;
import com.cadence.domain.OAuthFlowState;
import com.cadence.domain.Role;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** US1: connect lifecycle — encrypted storage, upsert, scope, callback negatives, audit (T023). */
class CalendarConnectIntegrationTest extends CalendarItBase {

    @Test
    void connect_storesEncryptedConnection_andAudits() {
        Member m = member("alex@x.com", Role.RECRUITER);
        CalendarConnection c = connect(m, CalendarProvider.GOOGLE, "alex@example.com");

        assertThat(c.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(c.getProviderAccountId()).isEqualTo("alex@example.com"); // decrypted via converter
        assertThat(c.getRefreshToken()).isEqualTo("refresh-google");

        // SC-002: raw-driver read is ciphertext, not the plaintext token/account.
        Document raw = mongoTemplate.getCollection("calendarConnections").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("refreshToken")).isNotEqualTo("refresh-google");
        assertThat(raw.getString("accessToken")).isNotEqualTo("access-google");
        assertThat(raw.getString("providerAccountId")).isNotEqualTo("alex@example.com");
        assertThat(piiCrypto.decrypt(raw.getString("refreshToken"))).isEqualTo("refresh-google");

        // FR-020: exactly one CALENDAR_CONNECTED audit row, no token/account in it.
        List<AuthAuditEvent> audits = mongoTemplate.findAll(AuthAuditEvent.class);
        assertThat(audits).filteredOn(a -> a.getEventType() == AuthEventType.CALENDAR_CONNECTED).hasSize(1);
    }

    @Test
    void reconnect_upsertsOneRow_bumpingVersion() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        CalendarConnection second = connect(m, CalendarProvider.GOOGLE, "alex2@example.com");

        assertThat(mongoTemplate.findAll(CalendarConnection.class)).hasSize(1); // FR-004 one-per-pair
        assertThat(second.getTokenVersion()).isGreaterThanOrEqualTo(2);
        assertThat(second.getProviderAccountId()).isEqualTo("alex2@example.com");
    }

    @Test
    void start_authorizeUrl_requestsFreeBusyScopeAndOfflineParams_noWriteScope() {
        Member m = member("alex@x.com", Role.RECRUITER);
        String url = connectionService.start(WS, m.getId(), CalendarProvider.GOOGLE);

        assertThat(url).contains("calendar.freebusy");          // FR-002 free/busy scope
        assertThat(url).contains("access_type=offline");        // offline params (refresh token)
        assertThat(url).contains("prompt=consent");
        assertThat(url).contains("code_challenge_method=S256"); // PKCE
        assertThat(url).doesNotContain("calendar.readonly");
        assertThat(url).doesNotContain("ReadWrite");            // no write scope
    }

    @Test
    void start_microsoftAuthorizeUrl_requestsWriteAndIdentityScope() {
        // F11 (D1): event WRITE needs Calendars.ReadWrite (Graph has no owned-events-only delegated scope);
        // openid/profile/email yield the id_token whose email/UPN getSchedule needs. §VIII-justified in plan.
        Member m = member("alex@x.com", Role.RECRUITER);
        String url = connectionService.start(WS, m.getId(), CalendarProvider.MICROSOFT);

        assertThat(url).contains("Calendars.ReadWrite"); // bi-directional write scope
        assertThat(url).contains("openid");              // identity scope -> id_token -> getSchedule mailbox
        assertThat(url).contains("offline_access");      // refresh token
        assertThat(url).contains("code_challenge_method=S256");
    }

    @Test
    void callback_unknownState_redirectsInvalidState_noRow() {
        Member m = member("alex@x.com", Role.RECRUITER);
        String redirect = connectionService.completeCallback(
            CalendarProvider.GOOGLE, "code", "no-such-state", null, WS, m.getId());
        assertThat(redirect).contains("error=invalid_state");
        assertThat(mongoTemplate.findAll(CalendarConnection.class)).isEmpty();
    }

    @Test
    void callback_stateBelongsToAnotherMember_rejected_noRow() {
        Member a = member("a@x.com", Role.RECRUITER);
        Member b = member("b@x.com", Role.RECRUITER);
        stubExchange(CalendarProvider.GOOGLE, "acc", "ref", 3600, "a@example.com");
        connectionService.start(WS, a.getId(), CalendarProvider.GOOGLE);
        OAuthFlowState state = mongoTemplate.findOne(new Query(), OAuthFlowState.class);

        // Member B presents A's state -> FR-018 cross-member-attach defense.
        String redirect = connectionService.completeCallback(
            CalendarProvider.GOOGLE, "code", state.getId(), null, WS, b.getId());
        assertThat(redirect).contains("error=invalid_state");
        assertThat(mongoTemplate.findAll(CalendarConnection.class)).isEmpty();
    }

    @Test
    void callback_consentDenied_redirects_noRow() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connectionService.start(WS, m.getId(), CalendarProvider.GOOGLE);
        OAuthFlowState state = mongoTemplate.findOne(new Query(), OAuthFlowState.class);
        String redirect = connectionService.completeCallback(
            CalendarProvider.GOOGLE, null, state.getId(), "access_denied", WS, m.getId());
        assertThat(redirect).contains("error=consent_denied");
        assertThat(mongoTemplate.findAll(CalendarConnection.class)).isEmpty();
    }

    @Test
    void callback_noRefreshToken_redirectsNoOfflineGrant_noRow() {
        Member m = member("alex@x.com", Role.RECRUITER);
        stubExchange(CalendarProvider.GOOGLE, "access-only", null, 3600, "a@example.com"); // no refresh_token
        connectionService.start(WS, m.getId(), CalendarProvider.GOOGLE);
        OAuthFlowState state = mongoTemplate.findOne(new Query(), OAuthFlowState.class);
        String redirect = connectionService.completeCallback(
            CalendarProvider.GOOGLE, "code", state.getId(), null, WS, m.getId());
        assertThat(redirect).contains("error=no_offline_grant");
        assertThat(mongoTemplate.findAll(CalendarConnection.class)).isEmpty();
    }

    @Test
    void callback_redirectAlwaysUsesConfiguredSpaBase() {
        Member m = member("alex@x.com", Role.RECRUITER);
        String redirect = connectionService.completeCallback(
            CalendarProvider.GOOGLE, "code", "bad", null, WS, m.getId());
        // Open-redirect negative (Security #2): host is the configured spaBaseUrl, never request-derived.
        assertThat(redirect).startsWith("http://localhost:4200/calendar/connections");
    }
}
