package com.cadence.integration;

import com.cadence.config.CalendarOAuthProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Shared authorization-code + refresh + revoke logic over {@link RestClient} (research D1). The
 * RestClient is built with bounded connect/read timeouts (Backend #6). Token-endpoint failures are
 * normalised to {@link OAuthTokenException} carrying the OAuth {@code error} code and HTTP status so
 * the failure classifier can act. NO token, code, or secret is ever logged here.
 */
public abstract class AbstractOAuthGateway implements OAuthGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected final CalendarOAuthProperties props;
    private final RestClient http;

    protected AbstractOAuthGateway(CalendarOAuthProperties props) {
        this.props = props;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(props.getConnectTimeout())
            .withReadTimeout(props.getReadTimeout());
        this.http = RestClient.builder()
            .requestFactory(ClientHttpRequestFactories.get(settings))
            .build();
    }

    /** The provider-specific config sub-block (google or microsoft). */
    protected abstract CalendarOAuthProperties.Provider config();

    /** Extra authorize-URL params that guarantee a refresh token (Google: access_type/prompt). */
    protected abstract Map<String, String> extraAuthParams();

    @Override
    public String authorizationUrl(String state, String codeChallenge, String redirectUri) {
        CalendarOAuthProperties.Provider c = config();
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(c.getAuthorizationUri())
            .queryParam("response_type", "code")
            .queryParam("client_id", c.getClientId())
            .queryParam("redirect_uri", redirectUri)
            .queryParam("scope", c.getScope())
            .queryParam("state", state)
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256");
        extraAuthParams().forEach(b::queryParam);
        return b.encode().toUriString();
    }

    @Override
    public TokenResponse exchangeCode(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("code_verifier", codeVerifier);
        addClientCreds(form);
        return parse(post(form));
    }

    @Override
    public TokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        addClientCreds(form);
        return parse(post(form));
    }

    @Override
    public void revoke(String token) {
        String uri = config().getRevocationUri();
        if (uri == null || uri.isBlank()) {
            return;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        try {
            http.post().uri(uri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
        } catch (RuntimeException e) {
            // Best-effort by contract: never throw from revoke, and never surface a provider response
            // body (which could carry the token) — the caller logs only the provider name.
        }
    }

    private void addClientCreds(MultiValueMap<String, String> form) {
        form.add("client_id", config().getClientId());
        form.add("client_secret", config().getClientSecret());
    }

    /** POST the form to the token endpoint, normalising failures to {@link OAuthTokenException}. */
    private String post(MultiValueMap<String, String> form) {
        try {
            return http.post().uri(config().getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        } catch (RestClientResponseException e) {
            throw new OAuthTokenException(
                extractOAuthError(e.getResponseBodyAsString()),
                e.getStatusCode().value(),
                "token endpoint returned " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new OAuthTokenException(null, null, "token endpoint unreachable", e);
        }
    }

    private static String extractOAuthError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body).get("error");
            return node == null ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static TokenResponse parse(String body) {
        try {
            JsonNode n = MAPPER.readTree(body);
            String accessToken = text(n, "access_token");
            String refreshToken = text(n, "refresh_token"); // null when the provider did not re-issue one
            long expiresIn = n.has("expires_in") ? n.get("expires_in").asLong() : 0L;
            String scope = text(n, "scope");
            String account = accountFromIdToken(text(n, "id_token"));
            return new TokenResponse(accessToken, refreshToken, expiresIn, scope, account);
        } catch (Exception e) {
            throw new OAuthTokenException(null, null, "malformed token response", e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * Best-effort account email/subject from an id_token's payload — DISPLAY ONLY, so the JWT signature
     * is NOT validated (we already know the member from their session; the token arrived over TLS).
     * Returns null if no id_token or no usable claim.
     */
    private static String accountFromIdToken(String idToken) {
        if (idToken == null) {
            return null;
        }
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
            for (String claim : new String[]{"email", "preferred_username", "upn", "sub"}) {
                String v = text(claims, claim);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
            return null;
        } catch (Exception e) {
            return null; // never fail a connect because the display name could not be derived
        }
    }
}
