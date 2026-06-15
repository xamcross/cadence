package com.cadence.service;

import com.cadence.config.CalendarOAuthProperties;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.ConnectionStatus;
import com.cadence.integration.CalendarNotConnectedException;
import com.cadence.integration.CalendarProviderTransientException;
import com.cadence.integration.CalendarReconnectRequiredException;
import com.cadence.integration.OAuthGateway;
import com.cadence.integration.OAuthTokenException;
import com.cadence.repository.CalendarConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Provides a currently-valid access token for a member+provider, refreshing transparently when expired
 * (research D5/D6). Refresh-then-CAS on {@code tokenVersion} makes the stored state exactly-one-writer
 * under concurrent calls (a 5-person free/busy storm cannot double-rotate the stored credential); a
 * transient failure leaves the row unchanged (no write); an {@code invalid_grant} flips the connection
 * to NEEDS_RECONNECTION. This is the working token-store behind the forward
 * {@code CalendarProviderClient} interface (F10/F11 widen it).
 */
@Service
public class CalendarTokenService {

    private static final Logger log = LoggerFactory.getLogger(CalendarTokenService.class);

    private final CalendarConnectionRepository connections;
    private final MongoTemplate mongo;
    private final AuthAuditService audit;
    private final OAuthFailureClassifier classifier;
    private final CalendarOAuthProperties props;
    private final Clock clock;
    private final Map<CalendarProvider, OAuthGateway> gateways;

    public CalendarTokenService(CalendarConnectionRepository connections, MongoTemplate mongo,
                                AuthAuditService audit, OAuthFailureClassifier classifier,
                                CalendarOAuthProperties props, Clock clock, List<OAuthGateway> gatewayList) {
        this.connections = connections;
        this.mongo = mongo;
        this.audit = audit;
        this.classifier = classifier;
        this.props = props;
        this.clock = clock;
        this.gateways = gatewayList.stream().collect(Collectors.toMap(OAuthGateway::id, Function.identity()));
    }

    /**
     * Returns a valid access token for the member+provider, refreshing if needed. Throws
     * {@link CalendarNotConnectedException} (no connection), {@link CalendarReconnectRequiredException}
     * (grant permanently invalid), or {@link CalendarProviderTransientException} (transient failure).
     */
    public String validAccessToken(String workspaceId, String memberId, CalendarProvider provider) {
        CalendarConnection conn = connections
            .findByWorkspaceIdAndMemberIdAndProvider(workspaceId, memberId, provider)
            .orElseThrow(CalendarNotConnectedException::new);
        if (conn.getStatus() == ConnectionStatus.NEEDS_RECONNECTION) {
            throw new CalendarReconnectRequiredException();
        }
        if (isFresh(conn)) {
            return conn.getAccessToken();
        }
        return refreshAndStore(conn, provider);
    }

    private boolean isFresh(CalendarConnection conn) {
        return conn.getAccessToken() != null
            && conn.getAccessTokenExpiresAt() != null
            && Instant.now(clock).plus(props.getAccessTokenSkew()).isBefore(conn.getAccessTokenExpiresAt());
    }

    private String refreshAndStore(CalendarConnection conn, CalendarProvider provider) {
        long version = conn.getTokenVersion();
        OAuthGateway.TokenResponse tokens;
        try {
            tokens = refreshWithRetry(provider, conn.getRefreshToken());
        } catch (OAuthTokenException e) {
            OAuthFailureClassifier.Classification cls = classifier.classify(e);
            if (cls == OAuthFailureClassifier.Classification.PERMANENT) {
                markNeedsReconnection(conn);
                throw new CalendarReconnectRequiredException();
            }
            // TRANSIENT (retries exhausted) or FATAL: no write performed -> row byte-identical (FR-016).
            log.warn("calendar refresh failed (no state change) {} {}",
                kv("memberId", conn.getMemberId()), kv("provider", provider.name()));
            throw new CalendarProviderTransientException("calendar refresh failed", e);
        }

        Instant now = Instant.now(clock);
        Update update = new Update()
            .set("accessToken", tokens.accessToken())
            .set("accessTokenExpiresAt", now.plusSeconds(tokens.expiresInSeconds()))
            .set("scope", tokens.scope())
            .set("lastRefreshAt", now)
            .set("updatedAt", now)
            .inc("tokenVersion", 1);
        // Persist a rotated refresh token ONLY when the response carried one (Security #8).
        if (tokens.refreshToken() != null) {
            update.set("refreshToken", tokens.refreshToken());
        }
        CalendarConnection won = mongo.findAndModify(
            new Query(Criteria.where("_id").is(conn.getId()).and("tokenVersion").is(version)),
            update, FindAndModifyOptions.options().returnNew(true), CalendarConnection.class);
        if (won != null) {
            return won.getAccessToken();
        }
        // Lost the CAS — another request already refreshed; use the winner's current token.
        CalendarConnection current = connections.findById(conn.getId())
            .orElseThrow(CalendarNotConnectedException::new);
        if (current.getStatus() == ConnectionStatus.NEEDS_RECONNECTION) {
            throw new CalendarReconnectRequiredException();
        }
        return current.getAccessToken();
    }

    /**
     * F10 seam (research D9 / plan-review B1): flip a member's connection to NEEDS_RECONNECTION when a
     * calendar API call reports a revoked or insufficient-scope grant. Reuses the guarded flip + audit;
     * a no-op if the connection is absent or already flipped (the guard matches only CONNECTED).
     */
    public void markNeedsReconnection(String workspaceId, String memberId, CalendarProvider provider) {
        connections.findByWorkspaceIdAndMemberIdAndProvider(workspaceId, memberId, provider)
            .ifPresent(this::markNeedsReconnection);
    }

    private void markNeedsReconnection(CalendarConnection conn) {
        Instant now = Instant.now(clock);
        // invalid_grant is terminal regardless of version, so flip on (id, status==CONNECTED) — NOT a
        // tokenVersion predicate (a concurrent refresh would otherwise make this silently no-op while we
        // still audit/throw). Audit only when this call actually performed the flip (no duplicate audit).
        CalendarConnection flipped = mongo.findAndModify(
            new Query(Criteria.where("_id").is(conn.getId()).and("status").is(ConnectionStatus.CONNECTED)),
            new Update().set("status", ConnectionStatus.NEEDS_RECONNECTION)
                .set("accessToken", null) // null the worthless token; retain refreshToken (Security #7)
                .set("updatedAt", now).inc("tokenVersion", 1),
            FindAndModifyOptions.options().returnNew(true), CalendarConnection.class);
        if (flipped != null) {
            audit.record(AuthEventType.CALENDAR_RECONNECT_REQUIRED, conn.getWorkspaceId(), conn.getMemberId(),
                "reconnect_required", null);
            log.info("calendar reconnect required {} {}",
                kv("memberId", conn.getMemberId()), kv("provider", conn.getProvider().name()));
        }
    }

    private OAuthGateway.TokenResponse refreshWithRetry(CalendarProvider provider, String refreshToken) {
        int attempt = 0;
        while (true) {
            try {
                return gateway(provider).refresh(refreshToken);
            } catch (OAuthTokenException e) {
                boolean transient_ = classifier.classify(e) == OAuthFailureClassifier.Classification.TRANSIENT;
                if (transient_ && attempt < props.getMaxRefreshRetries()) {
                    attempt++;
                    sleep(props.getRefreshRetryBackoff().toMillis() * attempt);
                    continue;
                }
                throw e;
            }
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private OAuthGateway gateway(CalendarProvider provider) {
        OAuthGateway g = gateways.get(provider);
        if (g == null) {
            throw new com.cadence.integration.UnsupportedProviderException();
        }
        return g;
    }
}
