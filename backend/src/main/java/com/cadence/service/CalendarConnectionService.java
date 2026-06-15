package com.cadence.service;

import com.cadence.config.AuthProperties;
import com.cadence.config.CalendarOAuthProperties;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.ConnectionStatus;
import com.cadence.domain.OAuthFlowState;
import com.cadence.integration.OAuthGateway;
import com.cadence.integration.OAuthTokenException;
import com.cadence.repository.CalendarConnectionRepository;
import com.cadence.repository.OAuthFlowStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Calendar connection lifecycle (F01.1): start (build the consent flow), completeCallback (single-use
 * state consume + bind + token exchange + encrypted upsert), list, disconnect, and disconnectAll (the
 * deactivation/erasure seam). All HTTP entry points act on the authenticated principal only — there is
 * no addressable path to another member's connection (FR-018, structural).
 */
@Service
public class CalendarConnectionService {

    private static final Logger log = LoggerFactory.getLogger(CalendarConnectionService.class);

    private final CalendarConnectionRepository connections;
    private final OAuthFlowStateRepository flowStates;
    private final MongoTemplate mongo;
    private final AuthAuditService audit;
    private final CalendarOAuthProperties oauthProps;
    private final AuthProperties authProps;
    private final Clock clock;
    private final Map<CalendarProvider, OAuthGateway> gateways;

    public CalendarConnectionService(CalendarConnectionRepository connections,
                                     OAuthFlowStateRepository flowStates, MongoTemplate mongo,
                                     AuthAuditService audit, CalendarOAuthProperties oauthProps,
                                     AuthProperties authProps, Clock clock, List<OAuthGateway> gatewayList) {
        this.connections = connections;
        this.flowStates = flowStates;
        this.mongo = mongo;
        this.audit = audit;
        this.oauthProps = oauthProps;
        this.authProps = authProps;
        this.clock = clock;
        this.gateways = gatewayList.stream().collect(Collectors.toMap(OAuthGateway::id, Function.identity()));
    }

    /** US1: begin a connection — mint a single-use state + PKCE, persist it, return the authorize URL. */
    public String start(String workspaceId, String memberId, CalendarProvider provider) {
        String state = com.cadence.security.SecureTokens.newToken();
        String verifier = com.cadence.security.SecureTokens.newToken();
        String challenge = pkceChallenge(verifier);

        Instant now = Instant.now(clock);
        OAuthFlowState flow = new OAuthFlowState();
        flow.setId(state);
        flow.setWorkspaceId(workspaceId);
        flow.setMemberId(memberId);
        flow.setProvider(provider);
        flow.setPkceVerifier(verifier);
        flow.setCreatedAt(now);
        flow.setExpiresAt(now.plus(oauthProps.getStateTtl()));
        flowStates.save(flow);

        return gateway(provider).authorizationUrl(state, challenge, redirectUri(provider));
    }

    /**
     * US1: the provider callback. Returns the SPA redirect URL (always built from the configured
     * spaBaseUrl — never request-derived, Security #2). No token/code appears in the result.
     */
    public String completeCallback(CalendarProvider provider, String code, String state, String error,
                                   String principalWorkspaceId, String principalMemberId) {
        // Single-use consume: atomic find-and-remove (no plain finder exists — Backend #3).
        OAuthFlowState flow = mongo.findAndRemove(
            new Query(Criteria.where("_id").is(state)), OAuthFlowState.class);

        if (flow == null
            || flow.getExpiresAt() == null || !flow.getExpiresAt().isAfter(Instant.now(clock))
            || !flow.getMemberId().equals(principalMemberId)
            || flow.getProvider() != provider) {
            return redirect("error", "invalid_state"); // FR-018 cross-member-attach defense
        }
        if (error != null && !error.isBlank()) {
            return redirect("error", "consent_denied");
        }

        OAuthGateway.TokenResponse tokens;
        try {
            // Exchange at the provider recorded in the consumed state (mix-up defense, Security #3).
            tokens = gateway(flow.getProvider()).exchangeCode(code, flow.getPkceVerifier(), redirectUri(provider));
        } catch (OAuthTokenException e) {
            log.warn("calendar code exchange failed {} {}", kv("memberId", principalMemberId),
                kv("provider", provider.name()));
            return redirect("error", "exchange_failed");
        }
        if (tokens.refreshToken() == null) {
            return redirect("error", "no_offline_grant"); // no durable credential — store nothing
        }

        upsertConnected(principalWorkspaceId, principalMemberId, provider, tokens);
        audit.record(AuthEventType.CALENDAR_CONNECTED, principalWorkspaceId, principalMemberId, "connected", null);
        log.info("calendar connected {} {}", kv("memberId", principalMemberId), kv("provider", provider.name()));
        return redirect("connected", provider.path());
    }

    /** The allowlisted invalid-state error redirect (used by the controller for an unsupported callback path). */
    public String invalidStateRedirect() {
        return redirect("error", "invalid_state");
    }

    /** US1: list the caller's own connections (decrypted connectedAccount via the converter; no tokens). */
    public List<CalendarConnection> list(String workspaceId, String memberId) {
        return connections.findByWorkspaceIdAndMemberId(workspaceId, memberId);
    }

    /** US3: disconnect one provider — best-effort revoke (FR-006), delete, audit. Idempotent. */
    public void disconnect(String workspaceId, String memberId, CalendarProvider provider) {
        connections.findByWorkspaceIdAndMemberIdAndProvider(workspaceId, memberId, provider)
            .ifPresent(c -> {
                bestEffortRevoke(provider, c.getRefreshToken());
                connections.delete(c);
                audit.record(AuthEventType.CALENDAR_DISCONNECTED, workspaceId, memberId, "disconnected", null);
            });
    }

    /**
     * US3 / D12 deactivation+erasure seam: delete ALL of a member's connections (both providers),
     * best-effort revoking each. Swallows provider-revoke failures so deactivation never aborts.
     */
    public void disconnectAll(String workspaceId, String memberId) {
        List<CalendarConnection> all = connections.findByWorkspaceIdAndMemberId(workspaceId, memberId);
        for (CalendarConnection c : all) {
            bestEffortRevoke(c.getProvider(), c.getRefreshToken());
        }
        if (!all.isEmpty()) {
            connections.deleteByWorkspaceIdAndMemberId(workspaceId, memberId);
            for (CalendarConnection c : all) {
                audit.record(AuthEventType.CALENDAR_DISCONNECTED, workspaceId, memberId, "disconnected", null);
            }
        }
    }

    private void upsertConnected(String workspaceId, String memberId, CalendarProvider provider,
                                 OAuthGateway.TokenResponse tokens) {
        Instant now = Instant.now(clock);
        Update update = new Update()
            .set("status", ConnectionStatus.CONNECTED)
            .set("refreshToken", tokens.refreshToken())
            .set("accessToken", tokens.accessToken())
            .set("accessTokenExpiresAt", now.plusSeconds(tokens.expiresInSeconds()))
            .set("scope", tokens.scope())
            .set("lastRefreshAt", null)
            .set("updatedAt", now)
            .inc("tokenVersion", 1)
            .setOnInsert("connectedAt", now);
        if (tokens.providerAccountId() != null) {
            update.set("providerAccountId", tokens.providerAccountId());
        }
        try {
            mongo.upsert(new Query(Criteria.where("workspaceId").is(workspaceId)
                    .and("memberId").is(memberId).and("provider").is(provider)),
                update, CalendarConnection.class);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Two concurrent first-time connects (double-click / two tabs) can race the unique index;
            // the winner's row is already correct and the connect is idempotent, so treat as success.
        }
    }

    private void bestEffortRevoke(CalendarProvider provider, String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        try {
            gateway(provider).revoke(refreshToken);
        } catch (RuntimeException e) {
            log.warn("calendar revoke failed (ignored) {}", kv("provider", provider.name()));
        }
    }

    private OAuthGateway gateway(CalendarProvider provider) {
        OAuthGateway g = gateways.get(provider);
        if (g == null) {
            throw new com.cadence.integration.UnsupportedProviderException();
        }
        return g;
    }

    private String redirectUri(CalendarProvider provider) {
        return oauthProps.getRedirectBaseUrl()
            + "/api/internal/calendar/connections/" + provider.path() + "/callback";
    }

    /** Build the SPA redirect from the configured base + a fixed path + an allowlisted param (Security #2). */
    private String redirect(String key, String value) {
        return UriComponentsBuilder.fromUriString(authProps.getSpaBaseUrl())
            .path("/calendar/connections")
            .queryParam(key, value)
            .encode().toUriString();
    }

    private static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
