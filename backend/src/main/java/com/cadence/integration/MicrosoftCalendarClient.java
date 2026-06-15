package com.cadence.integration;

import com.cadence.config.CalendarApiProperties;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventDetails;
import com.cadence.repository.CalendarConnectionRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Microsoft 365 / Outlook calendar adapter behind {@link CalendarProviderClient} (F11, research
 * D1/D2/D3/D4/D5/D6/D7/D8). Reads free/busy via Graph {@code getSchedule} ONLY, parsing each schedule
 * item's {@code start/end/status} via explicit JSON path reads (NEVER {@code subject}/{@code location} —
 * the no-content guarantee is parse-discipline, verified by a non-circular test, since getSchedule on the
 * caller's own mailbox can carry content). Writes Cadence interview events via {@code POST/PATCH/DELETE
 * /me/events}, idempotent via a deterministic {@code transactionId} (Graph dedup) + the caller's
 * unique-index claim; the SERVER-assigned event id is read back and returned. The access token always
 * comes from F01.1's {@link CalendarTokenService}; the member's mailbox SMTP/UPN (for getSchedule) comes
 * from the connection's {@code providerAccountId} and is used transiently, never logged/persisted (FR-025).
 * HTTP is raw {@code RestClient} (no Graph SDK) on a {@link JdkClientHttpRequestFactory} (required for
 * {@code PATCH}) with bounded timeouts and NO body-logging interceptor (FR-021/FR-023).
 */
@Component
public class MicrosoftCalendarClient implements CalendarProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CalendarTokenService tokenService;
    private final CalendarConnectionRepository connections;
    private final CalendarApiRetry retry;
    private final RestClient http;
    private final int availabilityViewInterval;

    public MicrosoftCalendarClient(CalendarTokenService tokenService, CalendarConnectionRepository connections,
                                   CalendarApiRetry retry, CalendarApiProperties props) {
        this.tokenService = tokenService;
        this.connections = connections;
        this.retry = retry;
        this.availabilityViewInterval = props.getGraphAvailabilityViewInterval();
        // JDK HttpClient factory (NOT the default) — the default cannot do HTTP PATCH (events.patch).
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(props.getConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(props.getReadTimeout());
        this.http = RestClient.builder()
            .baseUrl(props.getMicrosoft().getBaseUrl())
            .requestFactory(factory)
            .build();
    }

    @Override
    public CalendarProvider id() {
        return CalendarProvider.MICROSOFT;
    }

    @Override
    public String validAccessToken(String workspaceId, String memberId) {
        return tokenService.validAccessToken(workspaceId, memberId, CalendarProvider.MICROSOFT);
    }

    @Override
    public List<BusyInterval> queryFreeBusy(String workspaceId, String memberId,
                                            Instant windowStart, Instant windowEnd) {
        String mailbox = resolveMailbox(workspaceId, memberId);
        ObjectNode req = MAPPER.createObjectNode();
        req.putArray("schedules").add(mailbox);
        req.set("startTime", utcTime(windowStart));
        req.set("endTime", utcTime(windowEnd));
        req.put("availabilityViewInterval", availabilityViewInterval);
        // Token fetched INSIDE the retry so a multi-second backoff refreshes it (F10 lesson).
        String body = retry.execute(() -> {
            String token = token(workspaceId, memberId);
            return call(workspaceId, memberId, () -> http.post()
                .uri("/v1.0/me/calendar/getSchedule")
                .header("Authorization", "Bearer " + token)
                .header("Prefer", "outlook.timezone=\"UTC\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req.toString())
                .retrieve()
                .body(String.class));
        });
        return parseSchedule(body);
    }

    @Override
    public String createEvent(String workspaceId, String bookingRef, String memberId, EventDetails details) {
        // Deterministic transactionId (Graph retry/concurrent dedup, F11 D5). The id itself is assigned by
        // Graph and read back from the response — a client cannot set it.
        String transactionId = GoogleEventId.of(bookingRef, memberId);
        ObjectNode event = eventJson(details);
        event.put("transactionId", transactionId);
        String body = retry.execute(() -> {
            String token = token(workspaceId, memberId);
            return call(workspaceId, memberId, () -> http.post()
                .uri("/v1.0/me/events")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(event.toString())
                .retrieve()
                .body(String.class));
        });
        return readEventId(body);
    }

    @Override
    public void updateEvent(String workspaceId, String memberId, String providerEventId, EventDetails details) {
        ObjectNode patch = eventJson(details);
        try {
            retry.execute(() -> {
                String token = token(workspaceId, memberId);
                return call(workspaceId, memberId, () -> http.patch()
                    .uri("/v1.0/me/events/{id}", providerEventId)
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
    public void deleteEvent(String workspaceId, String memberId, String providerEventId) {
        try {
            retry.execute(() -> {
                String token = token(workspaceId, memberId);
                return call(workspaceId, memberId, () -> http.delete()
                    .uri("/v1.0/me/events/{id}", providerEventId)
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

    /**
     * The member's mailbox SMTP/UPN for the getSchedule {@code schedules} array (D2a). Comes from the F01.1
     * connection's (decrypted) {@code providerAccountId}; used transiently, never logged/persisted. A null/
     * blank or non-mailbox-shaped value (e.g. a {@code sub}-only id_token, or a pre-F11 read-only grant)
     * surfaces NEEDS_RECONNECTION so the member reconnects under the new scope, rather than a malformed query.
     */
    private String resolveMailbox(String workspaceId, String memberId) {
        CalendarConnection conn = connections
            .findByWorkspaceIdAndMemberIdAndProvider(workspaceId, memberId, CalendarProvider.MICROSOFT)
            .orElseThrow(CalendarNotConnectedException::new);
        String account = conn.getProviderAccountId();
        if (account == null || account.isBlank() || !account.contains("@")) {
            throw new CalendarReconnectRequiredException();
        }
        return account;
    }

    /** Obtain the F01.1 access token; re-wrap a token-layer transient as a calendar-API transient. */
    private String token(String workspaceId, String memberId) {
        try {
            return tokenService.validAccessToken(workspaceId, memberId, CalendarProvider.MICROSOFT);
        } catch (CalendarProviderTransientException e) {
            throw new CalendarApiException(true, null, "token_refresh_transient");
        }
        // CalendarReconnectRequiredException / CalendarNotConnectedException propagate unchanged.
    }

    /** Run one HTTP attempt, normalising Graph failures to typed exceptions (never logging the body). */
    private <T> T call(String workspaceId, String memberId, Supplier<T> attempt) {
        try {
            return attempt.get();
        } catch (RestClientResponseException e) {
            int httpStatus = e.getStatusCode().value();
            String code = extractCode(e.getResponseBodyAsString());
            switch (CalendarApiClassifier.classifyGraph(httpStatus, code)) {
                case RECONNECT:
                    // 401 / 403 insufficient-scope/revoked: flip the connection, surface reconnect, no retry.
                    tokenService.markNeedsReconnection(workspaceId, memberId, CalendarProvider.MICROSOFT);
                    throw new CalendarReconnectRequiredException();
                case TRANSIENT:
                    Duration retryAfter = parseRetryAfter(header(e, "Retry-After"), Instant.now());
                    throw new CalendarApiException(true, httpStatus, code, retryAfter);
                default:
                    throw new CalendarApiException(false, httpStatus, code, null);
            }
        } catch (ResourceAccessException e) {
            throw new CalendarApiException(true, null, "network");
        }
    }

    private ObjectNode eventJson(EventDetails d) {
        ObjectNode event = MAPPER.createObjectNode();
        if (d.title() != null) {
            event.put("subject", d.title());
        }
        if (d.location() != null) {
            event.set("location", MAPPER.createObjectNode().put("displayName", d.location()));
        }
        event.set("start", graphTime(d.startAt(), d.timeZone()));
        event.set("end", graphTime(d.endAt(), d.timeZone()));
        return event;
    }

    /** Graph dateTimeTimeZone for a write: local wall-clock for the event's IANA zone (DST-safe, D4). */
    private static ObjectNode graphTime(Instant instant, ZoneId zone) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("dateTime", LocalDateTime.ofInstant(instant, zone).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        n.put("timeZone", zone.getId()); // IANA — Graph accepts IANA identifiers
        return n;
    }

    /** Graph dateTimeTimeZone for a read request: UTC (so responses parse to unambiguous instants, D4). */
    private static ObjectNode utcTime(Instant instant) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("dateTime", LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        n.put("timeZone", "UTC");
        return n;
    }

    /**
     * Parse {@code value[0].scheduleItems[]} into {@link BusyInterval}s — reading ONLY {@code start}/
     * {@code end}/{@code status} via explicit path reads (subject/location are NEVER bound — D2/Security S5).
     * Any status other than {@code free} blocks the slot (D3 — fail safe).
     */
    private static List<BusyInterval> parseSchedule(String body) {
        List<BusyInterval> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        try {
            JsonNode value = MAPPER.readTree(body).path("value");
            if (!value.isArray() || value.isEmpty()) {
                return out;
            }
            JsonNode items = value.get(0).path("scheduleItems");
            for (JsonNode it : items) {
                String status = it.path("status").asText("");
                if ("free".equals(status)) {
                    continue; // only free is schedulable; everything else (busy/tentative/oof/...) is busy
                }
                Instant start = parseGraphInstant(it.path("start").path("dateTime").asText());
                Instant end = parseGraphInstant(it.path("end").path("dateTime").asText());
                out.add(new BusyInterval(start, end));
            }
        } catch (Exception e) {
            // A malformed getSchedule body is a fatal (non-transient) provider problem — never log the body.
            throw new CalendarApiException(false, null, "malformed_getschedule");
        }
        return out;
    }

    /** Graph response dateTime is UTC (we forced Prefer/UTC); tolerate an offset form too. */
    private static Instant parseGraphInstant(String dt) {
        try {
            return OffsetDateTime.parse(dt).toInstant();
        } catch (Exception ignore) {
            return LocalDateTime.parse(dt).toInstant(ZoneOffset.UTC);
        }
    }

    private static String readEventId(String body) {
        if (body == null || body.isBlank()) {
            throw new CalendarApiException(false, null, "create_no_id");
        }
        try {
            String id = MAPPER.readTree(body).path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw new CalendarApiException(false, null, "create_no_id");
            }
            return id;
        } catch (CalendarApiException e) {
            throw e;
        } catch (Exception e) {
            throw new CalendarApiException(false, null, "create_malformed");
        }
    }

    /** Graph error body is {@code {"error":{"code":"...","message":"..."}}}; read only the non-PII code. */
    private static String extractCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode code = MAPPER.readTree(body).path("error").get("code");
            return code == null ? null : code.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse a {@code Retry-After} header — delta-seconds OR HTTP-date (F11 D7). Null/malformed -> null. */
    static Duration parseRetryAfter(String header, Instant now) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String h = header.trim();
        try {
            long seconds = Long.parseLong(h);
            return Duration.ofSeconds(Math.max(0, seconds));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant when = OffsetDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration d = Duration.between(now, when);
                return d.isNegative() ? Duration.ZERO : d;
            } catch (Exception notDate) {
                return null;
            }
        }
    }

    private static String header(RestClientResponseException e, String name) {
        if (e.getResponseHeaders() == null) {
            return null;
        }
        return e.getResponseHeaders().getFirst(name);
    }

    private static int status(CalendarApiException e) {
        return e.getHttpStatus() == null ? -1 : e.getHttpStatus();
    }

    private static boolean isGone(int status) {
        return status == 404 || status == 410;
    }
}
