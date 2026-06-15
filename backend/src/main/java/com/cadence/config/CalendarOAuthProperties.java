package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code calendar.oauth.*} block (research D9). Explicit endpoint URIs (NOT issuer-uri — the
 * F01 eager-discovery footgun). Secrets come from Fly secrets in prod; dev defaults point at a local
 * stub. {@code connectTimeout}/{@code readTimeout} bound the RestClient so a hung provider socket
 * cannot stall a free/busy request (Backend #6).
 */
@ConfigurationProperties(prefix = "calendar.oauth")
public class CalendarOAuthProperties {

    /** Absolute base URL of THIS backend, used to build the provider redirect_uri. */
    private String redirectBaseUrl = "http://localhost:8080";
    private Duration stateTtl = Duration.ofMinutes(10);
    private Duration accessTokenSkew = Duration.ofSeconds(60);
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    private int maxRefreshRetries = 3;
    /** Base backoff between transient refresh retries (multiplied by the attempt number). */
    private Duration refreshRetryBackoff = Duration.ofMillis(50);

    private final Provider google = new Provider();
    private final Provider microsoft = new Provider();

    public String getRedirectBaseUrl() { return redirectBaseUrl; }
    public void setRedirectBaseUrl(String redirectBaseUrl) { this.redirectBaseUrl = redirectBaseUrl; }
    public Duration getStateTtl() { return stateTtl; }
    public void setStateTtl(Duration stateTtl) { this.stateTtl = stateTtl; }
    public Duration getAccessTokenSkew() { return accessTokenSkew; }
    public void setAccessTokenSkew(Duration accessTokenSkew) { this.accessTokenSkew = accessTokenSkew; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getMaxRefreshRetries() { return maxRefreshRetries; }
    public void setMaxRefreshRetries(int maxRefreshRetries) { this.maxRefreshRetries = maxRefreshRetries; }
    public Duration getRefreshRetryBackoff() { return refreshRetryBackoff; }
    public void setRefreshRetryBackoff(Duration refreshRetryBackoff) { this.refreshRetryBackoff = refreshRetryBackoff; }
    public Provider getGoogle() { return google; }
    public Provider getMicrosoft() { return microsoft; }

    public static class Provider {
        private String clientId = "";
        private String clientSecret = "";
        private String authorizationUri = "";
        private String tokenUri = "";
        private String revocationUri = "";
        private String scope = "";

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getAuthorizationUri() { return authorizationUri; }
        public void setAuthorizationUri(String authorizationUri) { this.authorizationUri = authorizationUri; }
        public String getTokenUri() { return tokenUri; }
        public void setTokenUri(String tokenUri) { this.tokenUri = tokenUri; }
        public String getRevocationUri() { return revocationUri; }
        public void setRevocationUri(String revocationUri) { this.revocationUri = revocationUri; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }
}
