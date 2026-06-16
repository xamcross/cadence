package com.cadence.api;

import com.cadence.config.EmailDeliveryProperties;
import com.cadence.service.EmailBounceService;
import com.cadence.service.EmailBounceService.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Inbound provider bounce/delivery/complaint webhook (F22, contract B / research D4) —
 * {@code POST /api/webhooks/email/events}. Public chain (no session, signature-gated): the dedicated
 * {@code @Order} security chain (SecurityConfig) routes this path {@code permitAll} + CSRF-exempt +
 * STATELESS, so the unauthenticated provider POST reaches the controller; the REAL auth is the in-controller
 * HMAC signature check, verified <b>before any state change</b> (a bad signature -> 401, no state change,
 * SC-008). Annotated {@code @PreAuthorize("permitAll()")} so the F02 RbacEndpointInventoryTest is satisfied
 * for an unauthenticated-by-design handler (the actual gate is the signature, not a role).
 *
 * <p>Parses ONLY {@code {eventId, providerMessageRef, type, occurredAt}} via explicit {@link JsonNode#path}
 * reads — never binds the provider's free-text {@code reason}/{@code description} (F11 parse-discipline, D10).
 * An unknown/cross-workspace ref or a duplicate event -> 200 ack, no state change (no existence oracle). The
 * webhook secret is never logged and never persisted (process-env only).
 */
@RestController
@RequestMapping("/api/webhooks/email")
public class EmailWebhookController {

    private static final Logger log = LoggerFactory.getLogger(EmailWebhookController.class);
    private static final String HMAC_ALG = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-Cadence-Signature";

    private final EmailBounceService bounce;
    private final EmailDeliveryProperties props;
    private final ObjectMapper mapper;

    public EmailWebhookController(EmailBounceService bounce, EmailDeliveryProperties props, ObjectMapper mapper) {
        this.bounce = bounce;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * The raw body is taken as a {@code String} so the HMAC is computed over the EXACT received bytes (a bound
     * POJO would re-serialize and break the signature). Signature verified first; only then is the body parsed.
     */
    @PostMapping("/events")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> events(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {

        String body = rawBody == null ? "" : rawBody;
        if (!signatureValid(body, signature)) {
            // Bad/missing signature -> 401, NO state change (SC-008). Never log the secret or the signature.
            log.warn("email webhook rejected — invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            // Malformed JSON after a valid signature: ack (the provider already authenticated) — no state change.
            log.warn("email webhook — unparseable body after valid signature {}",
                StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
            return ResponseEntity.ok().build();
        }

        // Accept either a single event object or an {events:[...]} / top-level array batch.
        JsonNode events = root.path("events");
        if (events.isArray()) {
            events.forEach(this::handleOne);
        } else if (root.isArray()) {
            root.forEach(this::handleOne);
        } else {
            handleOne(root);
        }
        return ResponseEntity.ok().build();
    }

    /** Read only the four whitelisted scalar fields; never bind/log the provider's free-text reason. */
    private void handleOne(JsonNode event) {
        String eventId = textOrNull(event.path("eventId"));
        String providerMessageRef = textOrNull(event.path("providerMessageRef"));
        EventType type = mapType(textOrNull(event.path("type")));
        bounce.process(eventId, providerMessageRef, type);
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    /** Map the parsed {@code type} enum-string to a value-free internal kind (never the free-text reason). */
    private static EventType mapType(String type) {
        if (type == null) {
            return EventType.UNKNOWN;
        }
        return switch (type.toLowerCase()) {
            case "delivered" -> EventType.DELIVERED;
            case "bounce", "hard_bounce", "hardbounce" -> EventType.HARD_BOUNCE;
            case "soft_bounce", "softbounce", "deferred" -> EventType.SOFT_BOUNCE;
            case "complaint", "spamreport", "spam_report" -> EventType.COMPLAINT;
            default -> EventType.UNKNOWN;
        };
    }

    /** Constant-time HMAC-SHA256 comparison of the raw body against the {@code X-Cadence-Signature} header. */
    private boolean signatureValid(String body, String signature) {
        String secret = props.getWebhookSecret();
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false; // fail-closed: an unconfigured secret never accepts an event
        }
        byte[] expected = hmac(secret, body);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(stripPrefix(signature));
        } catch (IllegalArgumentException e) {
            return false; // non-hex signature
        }
        return MessageDigest.isEqual(expected, provided); // constant-time
    }

    /** Tolerate a {@code sha256=} prefix (SendGrid/Mailgun style). */
    private static String stripPrefix(String signature) {
        int eq = signature.indexOf('=');
        return eq >= 0 ? signature.substring(eq + 1) : signature;
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
