package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 032 -- Freemius billing config (FR-018/FR-019). base-url/checkout-base-url point at live Freemius
 * in prod; tests point base-url at the JDK HttpServer stub via @DynamicPropertySource. apiBearer and
 * webhookSecret are app-level secrets bound from Fly env placeholders (the EmailDeliveryProperties
 * model) -- blank values fail closed (webhook rejects; checkout/claim error) rather than boot-fail.
 */
@ConfigurationProperties(prefix = "cadence.billing")
public class BillingProperties {

    private String baseUrl = "https://api.freemius.com";
    private String checkoutBaseUrl = "https://checkout.freemius.com";
    private String productId = "";
    private String teamPlanId = "";
    private String apiBearer = "";
    private String webhookSecret = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    private int retryMaxAttempts = 3;
    private Duration retryBaseBackoff = Duration.ofSeconds(2);

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getCheckoutBaseUrl() { return checkoutBaseUrl; }
    public void setCheckoutBaseUrl(String checkoutBaseUrl) { this.checkoutBaseUrl = checkoutBaseUrl; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getTeamPlanId() { return teamPlanId; }
    public void setTeamPlanId(String teamPlanId) { this.teamPlanId = teamPlanId; }
    public String getApiBearer() { return apiBearer; }
    public void setApiBearer(String apiBearer) { this.apiBearer = apiBearer; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public void setRetryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; }
    public Duration getRetryBaseBackoff() { return retryBaseBackoff; }
    public void setRetryBaseBackoff(Duration retryBaseBackoff) { this.retryBaseBackoff = retryBaseBackoff; }
}
