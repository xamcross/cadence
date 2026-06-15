package com.cadence.calendar;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-test Microsoft Graph calendar API stub backed by the JDK {@link HttpServer} (F11 D10 — a SIBLING of
 * {@link StubGoogleCalendar}, not a subclass: Graph paths/shapes, server-assigned event ids, {@code
 * transactionId} dedup, and {@code Retry-After} differ from Google's). Zero extra dependency, no WireMock
 * classpath conflict.
 *
 * <ul>
 *   <li>{@code POST /v1.0/me/calendar/getSchedule} -> 200 {@code {value:[{scheduleItems:[{status,start,
 *       end,subject,location}]}]}} carrying SEEDED subject/location (so SC-004 is non-circular — the
 *       adapter must drop them).
 *   <li>{@code POST /v1.0/me/events} -> 201 with a SERVER-generated id, deduped by {@code transactionId}
 *       (same id returned for a repeat — models Graph's retry dedup, FR-010).
 *   <li>{@code PATCH /v1.0/me/events/{id}} -> 200. {@code DELETE /v1.0/me/events/{id}} -> 204 (removes live).
 *   <li>{@link #program}/{@link #programError}/{@link #programRetryAfter} override the status for matching
 *       (method, path-substring) requests with a SEQUENCE (sticky on the last); error bodies use Graph's
 *       {@code {"error":{"code","message"}}} shape; an injectable {@code Retry-After} header (delta-seconds
 *       or HTTP-date).
 * </ul>
 */
final class StubGraphCalendar {

    private record BusyItem(Instant start, Instant end, String status, String subject, String location) {}
    private record Recorded(String method, String path, String body) {}
    private static final class Program {
        final String method; final String pathContains; final String code; final String message;
        final String retryAfter; final int[] statuses; final AtomicInteger idx = new AtomicInteger();
        Program(String method, String pathContains, String code, String message, String retryAfter, int[] statuses) {
            this.method = method; this.pathContains = pathContains; this.code = code;
            this.message = message; this.retryAfter = retryAfter; this.statuses = statuses;
        }
        int next() { return statuses[Math.min(idx.getAndIncrement(), statuses.length - 1)]; }
    }

    private static final Pattern EVENT_ID = Pattern.compile("/events/([^/?]+)");
    private static final DateTimeFormatter GRAPH = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS");

    private final HttpServer server;
    private final List<BusyItem> items = new ArrayList<>();
    private final List<Program> programs = new ArrayList<>();
    private final List<Recorded> requests = new ArrayList<>();
    private final Set<String> liveEvents = new LinkedHashSet<>();
    private final Map<String, String> byTransactionId = new HashMap<>();
    private final AtomicInteger eventSeq = new AtomicInteger();
    private volatile CountDownLatch gateArrivals;
    private volatile CountDownLatch gateRelease;

    StubGraphCalendar() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (this) {
                requests.add(new Recorded(method, path, body));
            }
            awaitGate();
            int status = statusFor(method, path);
            String retryAfter = retryAfterFor(method, path, status);
            String payload = bodyFor(method, path, body, status);
            if (status < 400 && "DELETE".equals(method)) {
                Matcher mm = EVENT_ID.matcher(path);
                if (mm.find()) {
                    synchronized (this) { liveEvents.remove(mm.group(1)); }
                }
            }
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            if (retryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
    }

    int port() { return server.getAddress().getPort(); }

    String baseUrl() { return "http://localhost:" + port(); }

    synchronized void reset() {
        items.clear();
        programs.clear();
        requests.clear();
        liveEvents.clear();
        byTransactionId.clear();
        eventSeq.set(0);
        gateArrivals = null;
        gateRelease = null;
    }

    /** Seed a busy/tentative/oof/... schedule item WITH content getSchedule must NOT surface (SC-004). */
    synchronized void addItem(Instant start, Instant end, String status, String sentinelSubject, String sentinelLocation) {
        items.add(new BusyItem(start, end, status, sentinelSubject, sentinelLocation));
    }

    /** Program a status sequence (sticky on the last) for matching (method, path-substring) requests. */
    synchronized void program(String method, String pathContains, int... statuses) {
        programs.add(0, new Program(method, pathContains, null, null, null, statuses)); // newest wins
    }

    /** Program a sequence with a Graph error {@code code} (e.g. ErrorAccessDenied) on the >=400 statuses. */
    synchronized void program(String method, String pathContains, String code, int... statuses) {
        programs.add(0, new Program(method, pathContains, code, null, null, statuses));
    }

    /** Program a sequence whose error body carries a (PII-bearing) {@code message} for the log-scan test. */
    synchronized void programError(String method, String pathContains, String code, String message, int... statuses) {
        programs.add(0, new Program(method, pathContains, code, message, null, statuses));
    }

    /** Program a sequence that returns a {@code Retry-After} header (delta-seconds or HTTP-date). */
    synchronized void programRetryAfter(String method, String pathContains, String retryAfter, int... statuses) {
        programs.add(0, new Program(method, pathContains, null, null, retryAfter, statuses));
    }

    synchronized long count(String method, String pathContains) {
        return requests.stream()
            .filter(r -> r.method().equals(method) && r.path().contains(pathContains)).count();
    }

    synchronized List<String> bodies(String method, String pathContains) {
        List<String> out = new ArrayList<>();
        for (Recorded r : requests) {
            if (r.method().equals(method) && r.path().contains(pathContains)) {
                out.add(r.body());
            }
        }
        return out;
    }

    synchronized Set<String> liveEvents() { return new LinkedHashSet<>(liveEvents); }

    /** Make the next {@code expected} requests all block until they have ALL arrived, then release. */
    void gate(int expected) {
        gateArrivals = new CountDownLatch(expected);
        gateRelease = new CountDownLatch(1);
    }

    // --- internals ---

    private synchronized Program matching(String method, String path) {
        for (Program p : programs) {
            if ((p.method == null || p.method.equals(method)) && path.contains(p.pathContains)) {
                return p;
            }
        }
        return null;
    }

    private synchronized int statusFor(String method, String path) {
        Program p = matching(method, path);
        if (p != null) {
            return p.next();
        }
        if ("DELETE".equals(method)) {
            return 204;
        }
        if ("POST".equals(method) && path.endsWith("/events")) {
            return 201;
        }
        return 200; // getSchedule / patch default
    }

    private synchronized String retryAfterFor(String method, String path, int status) {
        if (status < 400) {
            return null;
        }
        Program p = matching(method, path);
        return p == null ? null : p.retryAfter;
    }

    private synchronized String bodyFor(String method, String path, String reqBody, int status) {
        if (status == 204) {
            return "";
        }
        if (status >= 400) {
            Program p = matching(method, path);
            String code = p == null || p.code == null ? "ErrorInternalServerError" : p.code;
            String msg = p == null || p.message == null ? "stubbed error" : p.message;
            return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + msg + "\"}}";
        }
        if ("POST".equals(method) && path.endsWith("/getSchedule")) {
            return scheduleBody();
        }
        if ("POST".equals(method) && path.endsWith("/events")) {
            return createBody(reqBody);
        }
        return "{}"; // patch ok
    }

    /** Assign (or reuse, by transactionId) a server event id and mark it live. */
    private synchronized String createBody(String reqBody) {
        String txId = jsonField(reqBody, "transactionId");
        String id;
        if (txId != null && byTransactionId.containsKey(txId)) {
            id = byTransactionId.get(txId); // Graph dedup — return the existing event
        } else {
            id = "AAMk" + eventSeq.incrementAndGet();
            if (txId != null) {
                byTransactionId.put(txId, id);
            }
            liveEvents.add(id);
        }
        return "{\"id\":\"" + id + "\",\"subject\":\"stub\"}";
    }

    private synchronized String scheduleBody() {
        StringBuilder b = new StringBuilder("{\"value\":[{\"scheduleId\":\"primary\",\"scheduleItems\":[");
        for (int i = 0; i < items.size(); i++) {
            BusyItem it = items.get(i);
            if (i > 0) {
                b.append(",");
            }
            b.append("{\"status\":\"").append(it.status()).append("\"")
                .append(",\"start\":{\"dateTime\":\"").append(fmt(it.start())).append("\",\"timeZone\":\"UTC\"}")
                .append(",\"end\":{\"dateTime\":\"").append(fmt(it.end())).append("\",\"timeZone\":\"UTC\"}");
            if (it.subject() != null) {
                b.append(",\"subject\":\"").append(it.subject()).append("\"");
            }
            if (it.location() != null) {
                b.append(",\"location\":\"").append(it.location()).append("\"");
            }
            b.append("}");
        }
        return b.append("]}]}").toString();
    }

    private static String fmt(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(GRAPH);
    }

    private void awaitGate() {
        CountDownLatch arrivals = gateArrivals;
        CountDownLatch release = gateRelease;
        if (arrivals == null || release == null) {
            return;
        }
        arrivals.countDown();
        if (arrivals.getCount() == 0) {
            release.countDown();
        }
        try {
            release.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String jsonField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
