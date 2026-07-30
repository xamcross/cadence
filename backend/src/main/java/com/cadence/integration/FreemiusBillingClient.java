package com.cadence.integration;

import com.cadence.config.AuthProperties;
import com.cadence.config.BillingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * 032 -- Freemius adapter behind {@link BillingProvider} (FR-018). Own RestClient (JDK HttpClient
 * factory, the LeverAtsClient recipe), static ObjectMapper, explicit JsonNode.path reads only --
 * user_email/secret_key and every other unparsed field never bind (SENTINEL-seeded in the stub).
 * Endpoint + payload shape are integration-pending: pinned by StubFreemius, promoted to live
 * credentials in a later, separately-reviewed step. Response bodies are never logged.
 */
@Component
public class FreemiusBillingClient implements BillingProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Freemius datetimes are UTC "yyyy-MM-dd HH:mm:ss" (integration-pending). */
    private static final DateTimeFormatter FS_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BillingApiRetry retry;
    private final BillingProperties props;
    private final AuthProperties auth;
    private final RestClient http;

    public FreemiusBillingClient(BillingApiRetry retry, BillingProperties props, AuthProperties auth) {
        this.retry = retry;
        this.props = props;
        this.auth = auth;
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(props.getConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.getReadTimeout());
        this.http = RestClient.builder()
            .baseUrl(props.getBaseUrl())
            .requestFactory(factory)
            .build();
    }

    @Override
    public BillingLicense fetchLicense(String licenseId) {
        String body = retry.execute(() -> call(() -> http.get()
            .uri("/v1/products/{pid}/licenses/{lid}.json", props.getProductId(), licenseId)
            .header("Authorization", "Bearer " + props.getApiBearer())
            .retrieve()
            .body(String.class)));
        return parseLicense(body);
    }

    @Override
    public String checkoutUrl(String userEmail) {
        return props.getCheckoutBaseUrl()
            + "/product/" + props.getProductId()
            + "/plan/" + props.getTeamPlanId()
            + "/?user_email=" + URLEncoder.encode(userEmail, StandardCharsets.UTF_8)
            + "&readonly_user=true"
            + "&return_url=" + URLEncoder.encode(auth.getSpaBaseUrl() + "/admin/billing", StandardCharsets.UTF_8);
    }

    private BillingLicense parseLicense(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new BillingApiException(false, null, "malformed_license");
        }
        String id = root.path("id").asText(null);
        if (id == null) {
            throw new BillingApiException(false, null, "malformed_license");
        }
        Instant expiresAt = null;
        JsonNode expiration = root.path("expiration");
        if (!expiration.isMissingNode() && !expiration.isNull()) {
            try {
                expiresAt = LocalDateTime.parse(expiration.asText(), FS_DATETIME).toInstant(ZoneOffset.UTC);
            } catch (Exception e) {
                throw new BillingApiException(false, null, "malformed_license");
            }
        }
        return new BillingLicense(id,
            root.path("plan_id").asText(null),
            root.path("user_id").asText(null),
            expiresAt,
            root.path("is_cancelled").asBoolean(false));
    }

    /** Run one HTTP attempt, normalising failures to {@link BillingApiException} (never logging the body). */
    private <T> T call(Supplier<T> attempt) {
        try {
            return attempt.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || status >= 500) {
                throw new BillingApiException(true, status, "transient");
            }
            if (status == 401 || status == 403) {
                throw new BillingApiException(false, status, "auth");
            }
            if (status == 404) {
                throw new BillingApiException(false, status, "not_found");
            }
            throw new BillingApiException(false, status, "fatal");
        } catch (ResourceAccessException e) {
            throw new BillingApiException(true, null, "network");
        }
    }
}
