package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code calendar.api.*} block (F10 research D4/D8/D11). {@code google.base-url} is pointed at
 * the in-test JDK {@code HttpServer} stub via {@code @DynamicPropertySource}; {@code connect/read-timeout}
 * bound the calendar {@code RestClient}; {@code max-retries}/{@code retry-base-backoff} drive the
 * backoff+jitter retry; {@code freebusy-parallelism} bounds the panel fan-out; {@code max-window} caps an
 * availability query; {@code preview-window} is the self-preview horizon. No secrets here.
 */
@ConfigurationProperties(prefix = "calendar.api")
public class CalendarApiProperties {

    private final Google google = new Google();
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    private int maxRetries = 3;
    private Duration retryBaseBackoff = Duration.ofMillis(100);
    private int freebusyParallelism = 8;
    private Duration maxWindow = Duration.ofDays(60);
    private Duration previewWindow = Duration.ofDays(7);

    public Google getGoogle() { return google; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public Duration getRetryBaseBackoff() { return retryBaseBackoff; }
    public void setRetryBaseBackoff(Duration retryBaseBackoff) { this.retryBaseBackoff = retryBaseBackoff; }
    public int getFreebusyParallelism() { return freebusyParallelism; }
    public void setFreebusyParallelism(int freebusyParallelism) { this.freebusyParallelism = freebusyParallelism; }
    public Duration getMaxWindow() { return maxWindow; }
    public void setMaxWindow(Duration maxWindow) { this.maxWindow = maxWindow; }
    public Duration getPreviewWindow() { return previewWindow; }
    public void setPreviewWindow(Duration previewWindow) { this.previewWindow = previewWindow; }

    /** Per-provider API base URL (only Google for F10; F11 adds Microsoft Graph). */
    public static class Google {
        private String baseUrl = "https://www.googleapis.com";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
