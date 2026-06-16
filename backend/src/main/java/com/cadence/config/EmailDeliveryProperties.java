package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code cadence.email.*} block (F22). Holds the app-level default SMTP credentials (the
 * member/operational-mail sender and the candidate-send fallback — research D2), the inbound bounce
 * webhook shared secret (research D4), the ops-alert address, and the retry/reaper/sweep tuning.
 *
 * <p>Secrets ({@code smtp.password}, {@code webhookSecret}) are injected from Fly env secrets via the
 * {@code ${...}} bindings in {@code application.yml} — never inline, never logged. Auto-registers via
 * the existing {@code @ConfigurationPropertiesScan}.
 *
 * <p><b>Reaper-threshold invariant (research D5):</b> {@code reaperThreshold > smtp.readTimeout +
 * (retryBaseBackoff * 2^retryMaxAttempts max-backoff)} so the stale-{@code SENDING} reaper can never
 * race a live/retrying claim mid-flight (it would otherwise mark a still-in-progress send
 * {@code SENT_UNCONFIRMED}). Configure the threshold comfortably above the worst-case attempt latency.
 */
@ConfigurationProperties(prefix = "cadence.email")
public class EmailDeliveryProperties {

    private final Smtp smtp = new Smtp();

    /** App-level provider webhook signature/shared secret (CADENCE_EMAIL_WEBHOOK_SECRET). Never persisted. */
    private String webhookSecret;

    /** Where dead-letter/system alerts are sent (operational mail uses the app-level default sender). */
    private String opsAlertAddress;

    /** Max claim attempts before SENDING -> FAILED + dead-letter (data-model §3). */
    private int retryMaxAttempts = 3;

    /** Base backoff for the transient-retry exponential schedule (+jitter). */
    private Duration retryBaseBackoff = Duration.ofSeconds(30);

    /**
     * A row stuck SENDING older than this is reaped to SENT_UNCONFIRMED (no resend, crash window, FR-010).
     * MUST exceed {@code smtp.readTimeout + max-backoff} (the invariant above).
     */
    private Duration reaperThreshold = Duration.ofMinutes(10);

    /** Batch cap on the scheduled due-row read so a backlog cannot load an unbounded result set per tick. */
    private int sweepBatchLimit = 100;

    /** SMTP transport read timeout (part of the reaper-threshold invariant). */
    private Duration readTimeout = Duration.ofSeconds(10);

    public Smtp getSmtp() { return smtp; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public String getOpsAlertAddress() { return opsAlertAddress; }
    public void setOpsAlertAddress(String opsAlertAddress) { this.opsAlertAddress = opsAlertAddress; }

    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public void setRetryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; }

    public Duration getRetryBaseBackoff() { return retryBaseBackoff; }
    public void setRetryBaseBackoff(Duration retryBaseBackoff) { this.retryBaseBackoff = retryBaseBackoff; }

    public Duration getReaperThreshold() { return reaperThreshold; }
    public void setReaperThreshold(Duration reaperThreshold) { this.reaperThreshold = reaperThreshold; }

    public int getSweepBatchLimit() { return sweepBatchLimit; }
    public void setSweepBatchLimit(int sweepBatchLimit) { this.sweepBatchLimit = sweepBatchLimit; }

    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

    /** App-level default SMTP relay (provider host + API-key-as-password). Member/operational mail + fallback. */
    public static class Smtp {
        private String host;
        private int port = 587;
        private String username;
        /** Provider API key / SMTP password (CADENCE_EMAIL_SMTP_PASSWORD). Never logged. */
        private String password;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
