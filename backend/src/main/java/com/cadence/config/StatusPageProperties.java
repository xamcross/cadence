package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code cadence.status.*} block (F30, data-model). Auto-registers via the existing
 * {@code @ConfigurationPropertiesScan}. No secrets.
 *
 * <p>The candidate status-link is {@code {spaBaseUrl}{spaStatusBasePath}?token=...} (the F13
 * scheduling-link precedent). The per-IP rate limit reuses {@link SchedulingProperties#getRateLimitPerMinute()}
 * (the existing candidate-endpoint limiter), so F30 adds no new rate-limit key.
 */
@ConfigurationProperties(prefix = "cadence.status")
public class StatusPageProperties {

    /** SPA path for the candidate status page; appended to the F01 {@code spaBaseUrl}. */
    private String spaStatusBasePath = "/status";

    public String getSpaStatusBasePath() { return spaStatusBasePath; }
    public void setSpaStatusBasePath(String spaStatusBasePath) { this.spaStatusBasePath = spaStatusBasePath; }
}
