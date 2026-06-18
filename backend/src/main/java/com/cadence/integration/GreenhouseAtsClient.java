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
 * Greenhouse Harvest adapter behind {@link AtsConnector} (F40, contract C; the F10 {@code GoogleCalendarClient}
 * precedent). Raw {@code RestClient} on a {@link JdkClientHttpRequestFactory} (no Greenhouse SDK, Dependency
 * Policy / FR-026), bounded timeouts, NO body-logging interceptor. Auth is HTTP Basic with the API key as the
 * username (empty password). Inbound parsing reads ONLY the enumerated fields (FR-029) via explicit
 * {@code JsonNode.path} reads — never {@code attachments}/{@code custom_fields}/{@code eeoc}/{@code tags}.
 * Provider error bodies are reduced to a status/category (FR-003) and never logged.
 *
 * <p>The {@code externalRef} is the Greenhouse application id (the unit Cadence schedules against); the
 * write-back note is addressed to it.
 */
@Component
public class GreenhouseAtsClient implements AtsConnector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtsApiRetry retry;
    private final RestClient http;
    private final int pageLimit;

    public GreenhouseAtsClient(AtsApiRetry retry, AtsProperties props) {
        this.retry = retry;
        this.pageLimit = props.getSyncPageLimit();
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(props.getConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.getReadTimeout());
        this.http = RestClient.builder()
            .baseUrl(props.getGreenhouse().getBaseUrl())
            .requestFactory(factory)
            .build();
    }

    @Override
    public AtsProvider provider() {
        return AtsProvider.GREENHOUSE;
    }

    @Override
    public void verifyCredential(String workspaceId, String apiKey) {
        String auth = basic(apiKey);
        retry.execute(() -> call(() -> http.get()
            .uri("/v1/jobs?per_page=1")
            .header("Authorization", auth)
            .retrieve()
            .body(String.class)));
    }

    @Override
    public AtsFetchResult fetchCandidates(String workspaceId, String apiKey, String cursor) {
        String auth = basic(apiKey);
        StringBuilder uri = new StringBuilder("/v1/candidates?per_page=").append(pageLimit).append("&page=1");
        if (cursor != null && !cursor.isBlank()) {
            uri.append("&updated_after=").append(cursor);
        }
        String body = retry.execute(() -> call(() -> http.get()
            .uri(uri.toString())
            .header("Authorization", auth)
            .retrieve()
            .body(String.class)));
        return new AtsFetchResult(parseCandidates(body), null);
    }

    @Override
    public String pushActivity(String workspaceId, String apiKey, String externalRef, AtsActivity activity) {
        String auth = basic(apiKey);
        ObjectNode note = MAPPER.createObjectNode();
        note.put("body", activity.note()); // non-PII scheduling fact only (D5)
        note.put("visibility", "private");
        String body = retry.execute(() -> call(() -> http.post()
            .uri("/v1/candidates/{id}/activity_feed/notes", externalRef)
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
     * Flatten the candidate array to one {@link AtsCandidateRecord} per (first) application. Reads ONLY the
     * enumerated fields (FR-029) via explicit path reads — attachments/custom_fields/eeoc/tags are never touched.
     */
    private static List<AtsCandidateRecord> parseCandidates(String body) {
        List<AtsCandidateRecord> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        JsonNode arr;
        try {
            arr = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AtsApiException(false, false, null, "malformed_candidates");
        }
        for (JsonNode c : arr) {
            String first = c.path("first_name").asText("");
            String last = c.path("last_name").asText("");
            String name = (first + " " + last).trim();
            String email = firstValue(c.path("email_addresses"));
            String phone = firstValue(c.path("phone_numbers"));
            JsonNode app = c.path("applications").path(0);
            String externalRef = app.path("id").asText(null);
            JsonNode job = app.path("jobs").path(0);
            String jobId = job.path("id").asText(null);
            String jobTitle = job.path("name").asText(null);
            String stage = app.path("current_stage").path("name").asText(null);
            if (externalRef == null || externalRef.isBlank()) {
                continue; // no application -> nothing schedulable to import
            }
            out.add(new AtsCandidateRecord(externalRef,
                name.isBlank() ? null : name,
                email == null || email.isBlank() ? null : email,
                phone == null || phone.isBlank() ? null : phone,
                jobId, jobTitle, stage));
        }
        return out;
    }

    private static String firstValue(JsonNode arrayNode) {
        if (arrayNode != null && arrayNode.isArray() && !arrayNode.isEmpty()) {
            return arrayNode.get(0).path("value").asText(null);
        }
        return null;
    }

    private static String parseNoteId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode id = MAPPER.readTree(body).path("id");
            return id.isMissingNode() || id.isNull() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
