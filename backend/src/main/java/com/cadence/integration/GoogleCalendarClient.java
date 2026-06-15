package com.cadence.integration;

import com.cadence.config.CalendarApiProperties;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventDetails;
import com.cadence.service.CalendarTokenService;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Google Calendar adapter behind {@link CalendarProviderClient} (F10, research D2/D5/D6/D7/D8/D9). Reads
 * free/busy via the {@code freeBusy} endpoint ONLY (so no event content is ever received), and creates/
 * updates/deletes Cadence interview events idempotently. The access token always comes from F01.1's
 * {@link CalendarTokenService} (never held independently). HTTP is raw {@code RestClient} (no Google SDK,
 * Dependency Policy) with bounded timeouts and NO body-logging interceptor (FR-017b). Provider response/
 * error bodies are NEVER logged; only status + classified outcome.
 */
@Component
public class GoogleCalendarClient implements CalendarProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final CalendarTokenService tokenService;
    private final CalendarApiRetry retry;
    private final RestClient http;

    public GoogleCalendarClient(CalendarTokenService tokenService, CalendarApiRetry retry,
                                CalendarApiProperties props) {
        this.tokenService = tokenService;
        this.retry = retry;
        // JDK HttpClient factory (NOT the default HttpURLConnection one) — the latter cannot do HTTP
        // PATCH (events.patch), throwing ProtocolException. No body-logging interceptor (FR-017b).
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(props.getConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.getReadTimeout());
        this.http = RestClient.builder()
            .baseUrl(props.getGoogle().getBaseUrl())
            .requestFactory(factory)
            .build();
    }

    @Override
    public CalendarProvider id() {
        return CalendarProvider.GOOGLE;
    }

    @Override
    public String validAccessToken(String workspaceId, String memberId) {
        return tokenService.validAccessToken(workspaceId, memberId, CalendarProvider.GOOGLE);
    }

    @Override
    public List<BusyInterval> queryFreeBusy(String workspaceId, String memberId,
                                            Instant windowStart, Instant windowEnd) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("timeMin", windowStart.toString());
        req.put("timeMax", windowEnd.toString());
        req.putArray("items").addObject().put("id", "primary");
        // Token fetched INSIDE the retry (plan-review M1) so each attempt gets a fresh (possibly refreshed)
        // token — a multi-second backoff can outlive the prior token.
        String body = retry.execute(() -> {
            String token = token(workspaceId, memberId);
            return call(workspaceId, memberId, () -> http.post()
                .uri("/calendar/v3/freeBusy")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req.toString())
                .retrieve()
                .body(String.class));
        });
        return parseBusy(body);
    }

    @Override
    public String createEvent(String workspaceId, String bookingRef, String memberId, EventDetails details) {
        String eventId = GoogleEventId.of(bookingRef, memberId);
        ObjectNode event = eventJson(eventId, details);
        try {
            retry.execute(() -> {
                String token = token(workspaceId, memberId);
                return call(workspaceId, memberId, () -> http.post()
                    .uri("/calendar/v3/calendars/primary/events")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event.toString())
                    .retrieve()
                    .body(String.class));
            });
            return eventId;
        } catch (CalendarApiException e) {
            if (status(e) == 409) {
                return eventId; // id already exists -> idempotent success (D6)
            }
            throw e;
        }
    }

    @Override
    public void updateEvent(String workspaceId, String bookingRef, String memberId, EventDetails details) {
        String eventId = GoogleEventId.of(bookingRef, memberId);
        ObjectNode patch = eventJson(null, details);
        try {
            retry.execute(() -> {
                String token = token(workspaceId, memberId);
                return call(workspaceId, memberId, () -> http.patch()
                    .uri("/calendar/v3/calendars/primary/events/{id}", eventId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(patch.toString())
                    .retrieve()
                    .body(String.class));
            });
        } catch (CalendarApiException e) {
            if (isGone(status(e))) {
                return; // already gone -> idempotent success (FR-011)
            }
            throw e;
        }
    }

    @Override
    public void deleteEvent(String workspaceId, String bookingRef, String memberId) {
        String eventId = GoogleEventId.of(bookingRef, memberId);
        try {
            retry.execute(() -> {
                String token = token(workspaceId, memberId);
                return call(workspaceId, memberId, () -> http.delete()
                    .uri("/calendar/v3/calendars/primary/events/{id}", eventId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity());
            });
        } catch (CalendarApiException e) {
            if (isGone(status(e))) {
                return; // already gone -> idempotent success (FR-011)
            }
            throw e;
        }
    }

    // --- internals -------------------------------------------------------------------------------

    /** Obtain the F01.1 access token; re-wrap a token-layer transient as a calendar-API transient (M1). */
    private String token(String workspaceId, String memberId) {
        try {
            return tokenService.validAccessToken(workspaceId, memberId, CalendarProvider.GOOGLE);
        } catch (CalendarProviderTransientException e) {
            throw new CalendarApiException(true, null, "token_refresh_transient");
        }
        // CalendarReconnectRequiredException / CalendarNotConnectedException propagate unchanged.
    }

    /** Run one HTTP attempt, normalising provider failures to typed exceptions (never logging the body). */
    private <T> T call(String workspaceId, String memberId, Supplier<T> attempt) {
        try {
            return attempt.get();
        } catch (RestClientResponseException e) {
            int httpStatus = e.getStatusCode().value();
            String reason = extractReason(e.getResponseBodyAsString());
            switch (CalendarApiClassifier.classify(httpStatus, reason)) {
                case RECONNECT:
                    // Revoked or insufficient-scope (B1): flip the connection, surface reconnect, no retry.
                    tokenService.markNeedsReconnection(workspaceId, memberId, CalendarProvider.GOOGLE);
                    throw new CalendarReconnectRequiredException();
                case TRANSIENT:
                    throw new CalendarApiException(true, httpStatus, reason);
                default:
                    throw new CalendarApiException(false, httpStatus, reason);
            }
        } catch (ResourceAccessException e) {
            throw new CalendarApiException(true, null, "network");
        }
    }

    private ObjectNode eventJson(String eventId, EventDetails d) {
        ObjectNode event = MAPPER.createObjectNode();
        if (eventId != null) {
            event.put("id", eventId);
        }
        if (d.title() != null) {
            event.put("summary", d.title());
        }
        if (d.location() != null) {
            event.put("location", d.location());
        }
        event.set("start", timeNode(d.startAt(), d.timeZone()));
        event.set("end", timeNode(d.endAt(), d.timeZone()));
        return event;
    }

    private ObjectNode timeNode(Instant instant, java.time.ZoneId zone) {
        ObjectNode n = MAPPER.createObjectNode();
        // RFC-3339 with the zone's offset for this instant + the IANA zone id (DST-safe rendering, D5).
        n.put("dateTime", OffsetDateTime.ofInstant(instant, zone).format(RFC3339));
        n.put("timeZone", zone.getId());
        return n;
    }

    private static List<BusyInterval> parseBusy(String body) {
        List<BusyInterval> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        try {
            JsonNode busy = MAPPER.readTree(body).path("calendars").path("primary").path("busy");
            for (JsonNode b : busy) {
                Instant start = OffsetDateTime.parse(b.path("start").asText()).toInstant();
                Instant end = OffsetDateTime.parse(b.path("end").asText()).toInstant();
                out.add(new BusyInterval(start, end));
            }
        } catch (Exception e) {
            // A malformed free/busy body is a fatal (non-transient) provider problem — never log the body.
            throw new CalendarApiException(false, null, "malformed_freebusy");
        }
        return out;
    }

    /** Best-effort Google {@code error.errors[0].reason} (e.g. rateLimitExceeded, insufficientPermissions). */
    private static String extractReason(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode errors = MAPPER.readTree(body).path("error").path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                JsonNode r = errors.get(0).get("reason");
                return r == null ? null : r.asText();
            }
            JsonNode status = MAPPER.readTree(body).path("error").get("status");
            return status == null ? null : status.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static int status(CalendarApiException e) {
        return e.getHttpStatus() == null ? -1 : e.getHttpStatus();
    }

    private static boolean isGone(int status) {
        return status == 404 || status == 410;
    }
}
