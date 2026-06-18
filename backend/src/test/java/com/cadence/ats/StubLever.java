package com.cadence.ats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-test Lever Data API stub backed by the JDK {@link HttpServer} (the {@link StubGreenhouse} sibling, F41).
 * Zero extra dependency, no WireMock classpath conflict (the F01.1 Jackson conflict). Honors the Lever mapping
 * in contracts/ats-api.md section 1.
 *
 * <ul>
 *   <li>{@code GET /v1/opportunities} -> 200 {@code {"data":[...],"hasNext":false}} (also serves the
 *       {@code ?limit=1} credential verify) or a programmed 401 for a bad key. Each opportunity carries
 *       {@code id}/{@code name}/{@code emails[]}(strings)/{@code phones[].value}/{@code stage.{id,text}}/
 *       {@code applications[].posting.{id,text}}, AND seeded {@code links}/{@code tags}/{@code sources}/
 *       {@code origin}/{@code headline}/{@code archived} the FR-029 non-circular test asserts are never parsed.
 *   <li>{@code POST /v1/opportunities/{id}/notes} -> 201 {@code {"data":{"id":<generated>}}}; records the note.
 *   <li>{@link #program} overrides the status for matching (method, path-substring) requests with a SEQUENCE
 *       (sticky on the last element): e.g. {@code 503,503,200} or a persistent {@code 401}.
 * </ul>
 *
 * <p>JVM-lifetime singleton (NEVER stopped in an {@code @AfterAll} - the dead-port footgun). Reaped at JVM exit.
 */
public final class StubLever {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record SeededOpportunity(String externalRef, String name, String email, String phone,
                                     String jobId, String jobTitle, String stage) {}
    private record Recorded(String method, String path, String body) {}
    private static final class Program {
        final String method; final String pathContains; final int[] statuses;
        final AtomicInteger idx = new AtomicInteger();
        Program(String method, String pathContains, int[] statuses) {
            this.method = method; this.pathContains = pathContains; this.statuses = statuses;
        }
        int next() { return statuses[Math.min(idx.getAndIncrement(), statuses.length - 1)]; }
    }

    private static final Pattern NOTES_OPP_ID = Pattern.compile("/v1/opportunities/([^/?]+)/notes");

    private final HttpServer server;
    private final Map<String, SeededOpportunity> opportunities = new LinkedHashMap<>();
    private final List<Program> programs = new ArrayList<>();
    private final List<Recorded> requests = new ArrayList<>();
    private final Map<String, List<String>> notes = new LinkedHashMap<>();
    private final AtomicInteger noteIdSeq = new AtomicInteger();
    private volatile int retryAfterSeconds = -1;
    private volatile CountDownLatch gateArrivals;
    private volatile CountDownLatch gateRelease;

    public StubLever() {
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
            if (status < 400 && "POST".equals(method)) {
                Matcher m = NOTES_OPP_ID.matcher(path);
                if (m.find()) {
                    synchronized (this) {
                        notes.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(body);
                    }
                }
            }
            String payload = bodyFor(method, path, status);
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (status == 429 && retryAfterSeconds >= 0) {
                exchange.getResponseHeaders().add("Retry-After", Integer.toString(retryAfterSeconds));
            }
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public String baseUrl() { return "http://localhost:" + port(); }

    public synchronized void reset() {
        opportunities.clear();
        programs.clear();
        requests.clear();
        notes.clear();
        noteIdSeq.set(0);
        retryAfterSeconds = -1;
        gateArrivals = null;
        gateRelease = null;
    }

    /** Seed an opportunity (Lever's candidate-on-a-job) at the given stage. */
    public synchronized void addOpportunity(String externalRef, String name, String email, String phone,
                                            String jobId, String jobTitle, String stage) {
        opportunities.put(externalRef,
            new SeededOpportunity(externalRef, name, email, phone, jobId, jobTitle, stage));
    }

    /** Advance the seeded opportunity's stage (re-poll picks it up). */
    public synchronized void updateStage(String externalRef, String stage) {
        SeededOpportunity o = opportunities.get(externalRef);
        if (o != null) {
            opportunities.put(externalRef,
                new SeededOpportunity(o.externalRef(), o.name(), o.email(), o.phone(),
                    o.jobId(), o.jobTitle(), stage));
        }
    }

    /** Program a status sequence (sticky on the last) for matching (method, path-substring) requests. */
    public synchronized void program(String method, String pathContains, int... statuses) {
        programs.add(0, new Program(method, pathContains, statuses)); // newest wins
    }

    /** Emit this Retry-After (seconds) on any programmed 429. Negative disables (default). */
    public void retryAfterSeconds(int s) { this.retryAfterSeconds = s; }

    public synchronized long count(String method, String pathContains) {
        return requests.stream()
            .filter(r -> r.method().equals(method) && r.path().contains(pathContains)).count();
    }

    /** Recorded raw note bodies POSTed to the given opportunity's notes, in order. */
    public synchronized List<String> notes(String opportunityId) {
        return new ArrayList<>(notes.getOrDefault(opportunityId, List.of()));
    }

    /** Make the next {@code expected} requests all block until they have ALL arrived, then release. */
    public void gate(int expected) {
        gateArrivals = new CountDownLatch(expected);
        gateRelease = new CountDownLatch(1);
    }

    public void stop() {
        server.stop(0);
    }

    // --- internals ---

    private synchronized int statusFor(String method, String path) {
        for (Program p : programs) {
            if ((p.method == null || p.method.equals(method)) && path.contains(p.pathContains)) {
                return p.next();
            }
        }
        if ("POST".equals(method) && path.contains("/notes")) {
            return 201;
        }
        return 200; // opportunities verify / pull default
    }

    private synchronized String bodyFor(String method, String path, int status) {
        if (status >= 400) {
            // Provider error body carries a high-entropy sentinel so the PII scan proves the client never
            // persists/logs the raw provider response (FR-003) - it must reduce the failure to a category.
            return "{\"errors\":[{\"code\":\"error\",\"message\":\"SENTINELF41BODY_zz9 rejected\"}]}";
        }
        if ("POST".equals(method) && path.contains("/notes")) {
            return "{\"data\":{\"id\":\"note-" + noteIdSeq.incrementAndGet() + "\"}}";
        }
        if ("GET".equals(method) && path.contains("/v1/opportunities")) {
            return opportunitiesBody();
        }
        return "{}";
    }

    private synchronized String opportunitiesBody() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode data = root.putArray("data");
        for (SeededOpportunity o : opportunities.values()) {
            ObjectNode opp = data.addObject();
            opp.put("id", o.externalRef());
            opp.put("name", o.name());

            ArrayNode emails = opp.putArray("emails");
            emails.add(o.email());

            ArrayNode phones = opp.putArray("phones");
            ObjectNode ph = phones.addObject();
            ph.put("value", o.phone());
            ph.put("type", "mobile");

            // stage expanded -> {id, text}
            ObjectNode stage = opp.putObject("stage");
            stage.put("id", "stage-" + o.jobId());
            stage.put("text", o.stage());

            // applications expanded -> [{ posting: {id, text} }]
            ArrayNode apps = opp.putArray("applications");
            ObjectNode app = apps.addObject();
            ObjectNode posting = app.putObject("posting");
            posting.put("id", o.jobId());
            posting.put("text", o.jobTitle());

            // Seeded fields the client MUST NEVER parse (FR-029 / SC-005 non-circular).
            ArrayNode links = opp.putArray("links");
            links.add("https://example.invalid/SENTINEL_LINK");
            ArrayNode tags = opp.putArray("tags");
            tags.add("SENTINEL_TAG");
            ArrayNode sources = opp.putArray("sources");
            sources.add("SENTINEL_SOURCE");
            opp.put("origin", "SENTINEL_ORIGIN");
            opp.put("headline", "SENTINEL_HEADLINE");
            opp.put("archived", "SENTINEL_ARCHIVED_REASON");
        }
        root.put("hasNext", false);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
}
