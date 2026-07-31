package com.cadence.api;

import com.cadence.config.BillingProperties;
import com.cadence.domain.BillingWebhookEvent;
import com.cadence.integration.BillingApiException;
import com.cadence.repository.BillingWebhookEventRepository;
import com.cadence.service.BillingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 032 -- inbound Freemius webhook (FR-008/FR-009/FR-010), the EmailWebhookController posture:
 * unauthenticated-by-design (dedicated permitAll STATELESS CSRF-exempt chain in SecurityConfig;
 * @PreAuthorize("permitAll()") for the RBAC inventory), HMAC-SHA256 over the RAW body verified
 * constant-time BEFORE any parse or state change, fail-closed on a blank secret, generic 401 with
 * no detail. The event is a POKE: only id/type/license id are read; entitlement truth is re-fetched
 * from the API by BillingService. Processing order is refresh-then-record so a transient provider
 * failure returns 503 WITHOUT a dedup row -- Freemius retries and the retry reprocesses (refresh is
 * idempotent by construction). Payload bodies and the secret are never logged.
 */
@RestController
public class FreemiusWebhookController {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(FreemiusWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HMAC_ALG = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-Signature";

    private final BillingProperties props;
    private final BillingService billing;
    private final BillingWebhookEventRepository events;
    private final Clock clock;

    public FreemiusWebhookController(BillingProperties props, BillingService billing,
                                     BillingWebhookEventRepository events, Clock clock) {
        this.props = props;
        this.billing = billing;
        this.events = events;
        this.clock = clock;
    }

    @PostMapping("/api/webhooks/billing/freemius")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> receive(@RequestBody(required = false) String rawBody,
                                        @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        if (rawBody == null || !signatureValid(rawBody, signature)) {
            // Fixed message only -- never the secret, signature, or body (the EmailWebhookController
            // pattern). Without this line a misconfigured FREEMIUS_WEBHOOK_SECRET fails 100% silently.
            log.warn("billing webhook rejected -- invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.ok().build(); // signed but malformed: ack, never 5xx-loop the provider
        }
        String eventId = root.path("id").asText(null);
        String type = root.path("type").asText("");
        if (eventId == null || !type.startsWith("license.")) {
            return ResponseEntity.ok().build(); // irrelevant event family: ack (FR-010)
        }
        if (events.existsByEventId(eventId)) {
            return ResponseEntity.ok().build(); // replay: idempotent no-op (FR-009)
        }
        String licenseId = root.path("objects").path("license").path("id").asText(null);
        if (licenseId == null) {
            licenseId = root.path("license_id").asText(null); // integration-pending: both shapes pinned
        }
        if (licenseId != null) {
            try {
                billing.refreshByLicenseId(licenseId); // unbound -> no-op inside (FR-010)
            } catch (BillingApiException e) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build(); // provider retries
            }
        }
        try {
            events.insert(new BillingWebhookEvent(eventId, type, licenseId, Instant.now(clock), "processed"));
        } catch (DuplicateKeyException ignored) {
            // concurrent duplicate delivery -- the other worker recorded it
        }
        return ResponseEntity.ok().build();
    }

    /** Constant-time HMAC-SHA256 of the raw body vs X-Signature (hex; optional sha256= prefix). */
    private boolean signatureValid(String body, String signature) {
        String secret = props.getWebhookSecret();
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false; // fail-closed: an unconfigured secret never accepts an event
        }
        byte[] provided;
        try {
            int eq = signature.indexOf('=');
            provided = HexFormat.of().parseHex(eq >= 0 ? signature.substring(eq + 1) : signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, body), provided);
    }

    private static byte[] hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }
}
