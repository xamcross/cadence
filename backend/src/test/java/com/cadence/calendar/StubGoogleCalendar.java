package com.cadence.calendar;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-test Google Calendar API stub backed by the JDK {@link HttpServer} (D12 — sibling of StubProvider,
 * NOT a subclass: it adds method-aware matching, per-operation/per-eventId status SEQUENCES, and a
 * stateful event store). Zero extra dependency, no WireMock classpath conflict.
 *
 * <ul>
 *   <li>{@code POST /calendar/v3/freeBusy} -> 200 projecting ONLY {start,end} of seeded busy items (the
 *       seeded title/attendees are held server-side and never returned -> SC-004 non-circular).
 *   <li>{@code POST .../events} -> 201 (records the inserted id as a LIVE event).
 *   <li>{@code PATCH .../events/{id}} -> 200. {@code DELETE .../events/{id}} -> 204 (removes the live id).
 *   <li>{@link #program} overrides the status for matching (method, path-substring) requests with a
 *       SEQUENCE (sticky on the last element): e.g. {@code 429,429,200} or a persistent {@code 503}.
 * </ul>
 */
final class StubGoogleCalendar {

    private record BusyItem(Instant start, Instant end, String title, String attendee) {}
    private record Recorded(String method, String path, String body) {}
    private static final class Program {
        final String method; final String pathContains; final String reason;
        final int[] statuses; final AtomicInteger idx = new AtomicInteger();
        Program(String method, String pathContains, String reason, int[] statuses) {
            this.method = method; this.pathContains = pathContains; this.reason = reason; this.statuses = statuses;
        }
        int next() { return statuses[Math.min(idx.getAndIncrement(), statuses.length - 1)]; }
    }

    private static final Pattern EVENT_ID = Pattern.compile("/events/([^/?]+)");

    private final HttpServer server;
    private final List<BusyItem> busy = new ArrayList<>();
    private final List<Program> programs = new ArrayList<>();
    private final List<Recorded> requests = new ArrayList<>();
    private final Set<String> liveEvents = new LinkedHashSet<>();
    private volatile CountDownLatch gateArrivals;
    private volatile CountDownLatch gateRelease;

    StubGoogleCalendar() {
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
            String payload = bodyFor(method, path, body, status);
            if (status < 400 && "POST".equals(method) && path.endsWith("/events")) {
                String id = jsonField(body, "id");
                if (id != null) {
                    synchronized (this) { liveEvents.add(id); }
                }
            }
            if (status < 400 && "DELETE".equals(method)) {
                Matcher m = EVENT_ID.matcher(path);
                if (m.find()) {
                    synchronized (this) { liveEvents.remove(m.group(1)); }
                }
            }
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
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
        busy.clear();
        programs.clear();
        requests.clear();
        liveEvents.clear();
        gateArrivals = null;
        gateRelease = null;
    }

    /** Seed a busy interval WITH content that freeBusy must NOT return (SC-004 non-circular). */
    synchronized void addBusy(Instant start, Instant end, String sentinelTitle, String sentinelAttendee) {
        busy.add(new BusyItem(start, end, sentinelTitle, sentinelAttendee));
    }

    /** Program a status sequence (sticky on the last) for matching (method, path-substring) requests. */
    synchronized void program(String method, String pathContains, String reason, int... statuses) {
        programs.add(0, new Program(method, pathContains, reason, statuses)); // newest wins
    }

    synchronized void program(String method, String pathContains, int... statuses) {
        program(method, pathContains, null, statuses);
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

    private synchronized int statusFor(String method, String path) {
        for (Program p : programs) {
            if ((p.method == null || p.method.equals(method)) && path.contains(p.pathContains)) {
                return p.next();
            }
        }
        if ("DELETE".equals(method)) {
            return 204;
        }
        return 200; // freeBusy / insert / patch default
    }

    private synchronized String reasonFor(String method, String path) {
        for (Program p : programs) {
            if ((p.method == null || p.method.equals(method)) && path.contains(p.pathContains)) {
                return p.reason;
            }
        }
        return null;
    }

    private String bodyFor(String method, String path, String reqBody, int status) {
        if (status == 204) {
            return "";
        }
        if (status >= 400) {
            String reason = reasonFor(method, path);
            String errors = reason == null ? "" : ",\"errors\":[{\"reason\":\"" + reason + "\"}]";
            return "{\"error\":{\"code\":" + status + errors + "}}";
        }
        if ("POST".equals(method) && path.endsWith("/freeBusy")) {
            return freeBusyBody();
        }
        return "{}";
    }

    private synchronized String freeBusyBody() {
        StringBuilder b = new StringBuilder("{\"calendars\":{\"primary\":{\"busy\":[");
        for (int i = 0; i < busy.size(); i++) {
            BusyItem it = busy.get(i);
            if (i > 0) {
                b.append(",");
            }
            b.append("{\"start\":\"").append(it.start()).append("\",\"end\":\"").append(it.end()).append("\"}");
        }
        return b.append("]}}}").toString();
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
