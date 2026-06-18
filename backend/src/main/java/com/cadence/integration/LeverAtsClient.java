package com.cadence.integration;

import com.cadence.config.AtsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

/**
 * Lever Data API adapter behind {@link AtsConnector} (F41; the F40 {@code GreenhouseAtsClient} sibling). Raw
 * {@code RestClient} on a {@link JdkClientHttpRequestFactory} (no Lever SDK, Dependency Policy / FR-026/FR-030),
 * bounded timeouts, NO body-logging interceptor. Auth is HTTP Basic with the API key as the username (empty
 * password) -- the same shape as Greenhouse Harvest. Inbound parsing reads ONLY the enumerated fields (FR-029)
 * via explicit {@code JsonNode.path} reads -- never {@code links}/{@code tags}/{@code sources}/{@code origin}/
 * {@code headline}/{@code archived}/EEO (Lever's EEO data lives on a separate endpoint that is never called).
 * Provider error bodies are reduced to a status/category (FR-003) and never logged.
 *
 * <p>The {@code externalRef} is the Lever opportunity id (the unit Cadence schedules against); the write-back
 * note is addressed to it.
 *
 * <p><b>Live-promotion gap (FR-032)</b>: the exact Lever field paths and the notes endpoint's {@code perform_as}
 * requirement are honored by {@link com.cadence.integration.AtsConnector} against the in-test stub and MUST be
 * confirmed against live Lever (with the mandatory security re-review) before credential promotion.
 */
@Component
public class LeverAtsClient implements AtsConnector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtsApiRetry retry;
    private final RestClient http;
    private final int pageLimit;

    public LeverAtsClient(AtsApiRetry retry, AtsProperties props) {
        this.retry = retry;
        this.pageLimit = props.getSyncPageLimit();
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(props.getConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.getReadTimeout());
        this.http = RestClient.builder()
            .baseUrl(props.getLever().getBaseUrl())
            .requestFactory(factory)
            .build();
    }

    @Override
    public AtsProvider provider() {
        return AtsProvider.LEVER;
    }

    @Override
    public void verifyCredential(String workspaceId, String apiKey) {
        String auth = basic(apiKey);
        retry.execute(() -> call(() -> http.get()
            .uri("/v1/opportunities?limit=1")
            .header("Authorization", auth)
            .retrieve()
            .body(String.class)));
    }

    @Override
    public AtsFetchResult fetchCandidates(String workspaceId, String apiKey, String cursor) {
        String auth = basic(apiKey);
        StringBuilder uri = new StringBuilder("/v1/opportunities?limit=").append(pageLimit)
            .append("&expand=stage&expand=applications");
        if (cursor != null && !cursor.isBlank()) {
            uri.append("&offset=").append(cursor);
        }
        String body = retry.execute(() -> call(() -> http.get()
            .uri(uri.toString())
            .header("Authorization", auth)
            .retrieve()
            .body(String.class)));
        return parse(body);
    }

    @Override
    public String pushActivity(String workspaceId, String apiKey, String externalRef, AtsActivity activity) {
        String auth = basic(apiKey);
        ObjectNode note = MAPPER.createObjectNode();
        note.put("value", activity.note()); // non-PII scheduling fact only (D5/FR-029)
        String body = retry.execute(() -> call(() -> http.post()
            .uri("/v1/opportunities/{id}/notes", externalRef)
            .header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON)
            .body(note.toString())
            .retrieve()
            .body(String.class)));
        return parseNoteId(body);
    }

    // --- internals -------------------------------------------------------------------------------

    private static String basic(String apiKey) {
        String raw = apiKey + ":";
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Run one HTTP attempt, normalising provider failures to {@link AtsApiException} (never logging the body). */
    private <T> T call(Supplier<T> attempt) {
        try {
            return attempt.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            Duration retryAfter = parseRetryAfter(e);
            switch (AtsApiClassifier.classify(status)) {
                case AUTH:
                    throw new AtsApiException(false, true, status, "auth");
                case TRANSIENT:
                    throw new AtsApiException(true, false, status, "transient", retryAfter);
                default:
                    throw new AtsApiException(false, false, status, "fatal");
            }
        } catch (ResourceAccessException e) {
            throw new AtsApiException(true, false, null, "network");
        }
    }

    private static Duration parseRetryAfter(RestClientResponseException e) {
        try {
            String h = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
            if (h == null || h.isBlank()) {
                return null;
            }
            return Duration.ofSeconds(Long.parseLong(h.trim()));
        } catch (RuntimeException ex) {
            return null; // HTTP-date form or malformed -> ignore, fall back to backoff
        }
    }

    /**
     * Flatten the opportunity list ({@code data[]}) to one {@link AtsCandidateRecord} per opportunity. Reads ONLY
     * the enumerated fields (FR-029) via explicit path reads -- links/tags/sources/origin/headline/archived/EEO
     * are never touched. Pagination via {@code hasNext}/{@code next} (the cursor for the next offset).
     */
    private AtsFetchResult parse(String body) {
        List<AtsCandidateRecord> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return new AtsFetchResult(out, null);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AtsApiException(false, false, null, "malformed_candidates");
        }
        for (JsonNode o : root.path("data")) {
            String externalRef = o.path("id").asText(null);
            if (externalRef == null || externalRef.isBlank()) {
                continue; // no opportunity id -> nothing schedulable to import
            }
            String name = o.path("name").asText(null);
            String email = firstEmail(o.path("emails"));
            String phone = firstPhone(o.path("phones"));
            String stage = o.path("stage").path("text").asText(null); // expand=stage -> {id, text}
            JsonNode posting = o.path("applications").path(0).path("posting"); // expand=applications
            String jobId = posting.isObject() ? posting.path("id").asText(null) : asTextOrNull(posting);
            if (jobId == null) {
                jobId = asTextOrNull(o.path("postings").path(0)); // fallback: top-level posting id
            }
            String jobTitle = posting.isObject() ? posting.path("text").asText(null) : null;
            out.add(new AtsCandidateRecord(externalRef,
                blankToNull(name), blankToNull(email), blankToNull(phone), jobId, jobTitle, blankToNull(stage)));
        }
        boolean hasNext = root.path("hasNext").asBoolean(false);
        String next = hasNext ? asTextOrNull(root.path("next")) : null;
        return new AtsFetchResult(out, next);
    }

    /** Lever {@code emails} is an array of plain strings. */
    private static String firstEmail(JsonNode emails) {
        if (emails != null && emails.isArray() && !emails.isEmpty()) {
            return emails.get(0).asText(null);
        }
        return null;
    }

    /** Lever {@code phones} is an array of {@code {value, type}} objects (or plain strings, defensively). */
    private static String firstPhone(JsonNode phones) {
        if (phones != null && phones.isArray() && !phones.isEmpty()) {
            JsonNode first = phones.get(0);
            return first.isObject() ? first.path("value").asText(null) : first.asText(null);
        }
        return null;
    }

    private static String asTextOrNull(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String parseNoteId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode id = root.has("data") ? root.path("data").path("id") : root.path("id");
            return id.isMissingNode() || id.isNull() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
