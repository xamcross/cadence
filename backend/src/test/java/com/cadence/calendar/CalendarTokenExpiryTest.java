package com.cadence.calendar;

import com.cadence.auth.MutableClock;
import com.cadence.config.CalendarOAuthProperties;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.ConnectionStatus;
import com.cadence.integration.OAuthGateway;
import com.cadence.repository.CalendarConnectionRepository;
import com.cadence.service.AuthAuditService;
import com.cadence.service.CalendarTokenService;
import com.cadence.service.OAuthFailureClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T034: pure Mockito unit for the expiry+skew refresh decision (FR-012). No Mongo. Asserts a token is
 * served from cache when comfortably fresh, but a refresh fires once the access token is null, expired,
 * or within the skew buffer of expiring.
 */
class CalendarTokenExpiryTest {

    private static final Instant NOW = Instant.parse("2026-06-14T12:00:00Z");
    private static final String WS = "ws1";
    private static final String MEMBER = "m1";

    private CalendarConnectionRepository repo;
    private MongoTemplate mongo;
    private OAuthGateway gateway;
    private CalendarTokenService service;

    @BeforeEach
    void setUp() {
        repo = mock(CalendarConnectionRepository.class);
        mongo = mock(MongoTemplate.class);
        gateway = mock(OAuthGateway.class);
        when(gateway.id()).thenReturn(CalendarProvider.GOOGLE);
        AuthAuditService audit = mock(AuthAuditService.class);
        CalendarOAuthProperties props = new CalendarOAuthProperties(); // accessTokenSkew = 60s default
        MutableClock clock = new MutableClock(NOW);
        service = new CalendarTokenService(repo, mongo, audit, new OAuthFailureClassifier(),
            props, clock, List.of(gateway));
    }

    private CalendarConnection conn(String accessToken, Instant expiresAt) {
        CalendarConnection c = new CalendarConnection();
        c.setId("c1");
        c.setWorkspaceId(WS);
        c.setMemberId(MEMBER);
        c.setProvider(CalendarProvider.GOOGLE);
        c.setStatus(ConnectionStatus.CONNECTED);
        c.setRefreshToken("refresh");
        c.setAccessToken(accessToken);
        c.setAccessTokenExpiresAt(expiresAt);
        c.setTokenVersion(1);
        return c;
    }

    private void stubRefreshWins() {
        when(gateway.refresh(any())).thenReturn(new OAuthGateway.TokenResponse("refreshed", "refresh", 3600, "s", null));
        CalendarConnection won = conn("refreshed", NOW.plusSeconds(3600));
        won.setTokenVersion(2);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
            any(Class.class))).thenReturn(won);
    }

    @Test
    void comfortablyFresh_servesFromCache_noRefresh() {
        when(repo.findByWorkspaceIdAndMemberIdAndProvider(WS, MEMBER, CalendarProvider.GOOGLE))
            .thenReturn(Optional.of(conn("cached", NOW.plusSeconds(3600))));
        assertThat(service.validAccessToken(WS, MEMBER, CalendarProvider.GOOGLE)).isEqualTo("cached");
        verify(gateway, never()).refresh(any());
    }

    @Test
    void withinSkewBuffer_refreshes() {
        // expiry only 30s away, skew is 60s -> treated as stale.
        when(repo.findByWorkspaceIdAndMemberIdAndProvider(WS, MEMBER, CalendarProvider.GOOGLE))
            .thenReturn(Optional.of(conn("cached", NOW.plusSeconds(30))));
        stubRefreshWins();
        assertThat(service.validAccessToken(WS, MEMBER, CalendarProvider.GOOGLE)).isEqualTo("refreshed");
        verify(gateway).refresh(any());
    }

    @Test
    void expired_refreshes() {
        when(repo.findByWorkspaceIdAndMemberIdAndProvider(WS, MEMBER, CalendarProvider.GOOGLE))
            .thenReturn(Optional.of(conn("cached", NOW.minusSeconds(10))));
        stubRefreshWins();
        assertThat(service.validAccessToken(WS, MEMBER, CalendarProvider.GOOGLE)).isEqualTo("refreshed");
        verify(gateway).refresh(any());
    }

    @Test
    void nullAccessToken_refreshes() {
        when(repo.findByWorkspaceIdAndMemberIdAndProvider(WS, MEMBER, CalendarProvider.GOOGLE))
            .thenReturn(Optional.of(conn(null, NOW.plusSeconds(3600))));
        stubRefreshWins();
        assertThat(service.validAccessToken(WS, MEMBER, CalendarProvider.GOOGLE)).isEqualTo("refreshed");
        verify(gateway).refresh(any());
    }
}
