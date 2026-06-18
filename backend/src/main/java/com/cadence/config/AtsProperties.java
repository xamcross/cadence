package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code cadence.ats.*} block (F40 ATS Integration -- Greenhouse). {@code greenhouse.base-url}
 * is pointed at the in-test JDK {@code HttpServer} stub ({@code StubGreenhouse}) via
 * {@code @DynamicPropertySource}; {@code connect/read-timeout} bound the ATS {@code RestClient};
 * {@code retry-max-attempts}/{@code retry-base-backoff} drive the backoff+jitter retry;
 * {@code poll-interval} paces the inbound sync scan; {@code sync-page-limit}/{@code writeback-batch-limit}
 * bound the per-run fan-out; {@code reaper-threshold} reconciles a stuck in-flight write-back;
 * {@code ops-alert-address} is the dead-letter notification target. The API key is NOT here (it is a
 * per-workspace encrypted secret on {@code atsConnections}). No secrets in this class.
 *
 * <p>Auto-registers via the existing {@code @ConfigurationPropertiesScan} on {@code CadenceApplication}.
 */
@ConfigurationProperties(prefix = "cadence.ats")
public class AtsProperties {

    private final Greenhouse greenhouse = new Greenhouse();
    private final Lever lever = new Lever();
    private Duration pollInterval = Duration.ofMinutes(5);
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    private int retryMaxAttempts = 3;
    private Duration retryBaseBackoff = Duration.ofSeconds(2);
    private int syncPageLimit = 100;
    private int writebackBatchLimit = 100;
    private Duration reaperThreshold = Duration.ofMinutes(10);
    private String opsAlertAddress = "ops@localhost";

    public Greenhouse getGreenhouse() { return greenhouse; }

    public Lever getLever() { return lever; }

    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public void setRetryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; }

    public Duration getRetryBaseBackoff() { return retryBaseBackoff; }
    public void setRetryBaseBackoff(Duration retryBaseBackoff) { this.retryBaseBackoff = retryBaseBackoff; }

    public int getSyncPageLimit() { return syncPageLimit; }
    public void setSyncPageLimit(int syncPageLimit) { this.syncPageLimit = syncPageLimit; }

    public int getWritebackBatchLimit() { return writebackBatchLimit; }
    public void setWritebackBatchLimit(int writebackBatchLimit) { this.writebackBatchLimit = writebackBatchLimit; }

    public Duration getReaperThreshold() { return reaperThreshold; }
    public void setReaperThreshold(Duration reaperThreshold) { this.reaperThreshold = reaperThreshold; }

    public String getOpsAlertAddress() { return opsAlertAddress; }
    public void setOpsAlertAddress(String opsAlertAddress) { this.opsAlertAddress = opsAlertAddress; }

    /** Greenhouse Harvest API base URL (F40). Pointed at the in-test JDK HttpServer stub via @DynamicPropertySource. */
    public static class Greenhouse {
        private String baseUrl = "https://harvest.greenhouse.io";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    /** Lever Data API base URL (F41). Pointed at the in-test JDK HttpServer stub via @DynamicPropertySource. */
    public static class Lever {
        private String baseUrl = "https://api.lever.co";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
