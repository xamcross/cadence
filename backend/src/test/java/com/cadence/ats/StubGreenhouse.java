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
 * In-test Greenhouse Harvest API stub backed by the JDK {@link HttpServer} (sibling of StubGoogleCalendar,
 * NOT a subclass: it adds method-aware matching, per-operation status SEQUENCES, a seeded candidate /
 * application / job store, and write-back note recording). Zero extra dependency, no WireMock classpath
 * conflict (the F01.1 Jackson conflict). Honors the contract in contracts/ats-api.md section C.
 *
 * <ul>
 *   <li>{@code GET /v1/jobs} -> 200 {@code []} (credential verify) or a programmed 401 for a bad key.
 *   <li>{@code GET /v1/candidates} -> 200 array of candidate objects, each with nested {@code applications[]}
 *       (carrying {@code id}, {@code jobs[].{id,name}}, {@code current_stage.name}), plus top-level
 *       {@code first_name}/{@code last_name}/{@code email_addresses[].value}/{@code phone_numbers[].value},
 *       AND seeded {@code attachments}/{@code custom_fields}/{@code eeoc} the FR-029 non-circular test asserts
 *       are never parsed.
 *   <li>{@code POST /v1/candidates/{id}/activity_feed/notes} -> 201 {@code {"id":<generated>}}; records the note.
 *   <li>{@link #program} overrides the status for matching (method, path-substring) requests with a SEQUENCE
 *       (sticky on the last element): e.g. {@code 503,503,200} or a persistent {@code 401}.
 * </ul>
 */
public final class StubGreenhouse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record SeededCandidate(String externalRef, String firstName, String lastName, String email,
                                   String phone, String jobId, String jobTitle, String stage) {}
    private record Recorded(String method, String path, String body) {}
    private static final class Program {
        final String method; final String pathContains; final int[] statuses;
        final AtomicInteger idx = new AtomicInteger();
        Program(String method, String pathContains, int[] statuses) {
            this.method = method; this.pathContains = pathContains; this.statuses = statuses;
        }
        int next() { return statuses[Math.min(idx.getAndIncrement(), statuses.length - 1)]; }
    }

    private static final Pattern NOTES_CANDIDATE_ID =
        Pattern.compile("/v1/candidates/([^/?]+)/activity_feed/notes");

    private final HttpServer server;
    // externalRef -> seeded candidate (insertion-ordered for deterministic fetch responses).
    private final Map<String, SeededCandidate> candidates = new LinkedHashMap<>();
    private final List<Program> programs = new ArrayList<>();
    private final List<Recorded> requests = new ArrayList<>();
    // candidateId -> recorded note bodies posted to its activity feed.
    private final Map<String, List<String>> notes = new LinkedHashMap<>();
    private final AtomicInteger noteIdSeq = new AtomicInteger();
    private volatile int retryAfterSeconds = -1;
    private volatile CountDownLatch gateArrivals;
    private volatile CountDownLatch gateRelease;

    public StubGreenhouse() {
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
                Matcher m = NOTES_CANDIDATE_ID.matcher(path);
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
        candidates.clear();
        programs.clear();
        requests.clear();
        notes.clear();
        noteIdSeq.set(0);
        retryAfterSeconds = -1;
        gateArrivals = null;
        gateRelease = null;
    }

    /** Seed a candidate with one application on the given job at the given stage. */
    public synchronized void addCandidate(String externalRef, String firstName, String lastName,
                                          String email, String phone, String jobId, String jobTitle, String stage) {
        candidates.put(externalRef,
            new SeededCandidate(externalRef, firstName, lastName, email, phone, jobId, jobTitle, stage));
    }

    /** Advance the seeded candidate's current stage (re-poll picks it up). */
    public synchronized void updateStage(String externalRef, String stage) {
        SeededCandidate c = candidates.get(externalRef);
        if (c != null) {
            candidates.put(externalRef,
                new SeededCandidate(c.externalRef(), c.firstName(), c.lastName(), c.email(), c.phone(),
                    c.jobId(), c.jobTitle(), stage));
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

    /** Recorded raw note bodies POSTed to the given candidate's activity feed, in order. */
    public synchronized List<String> notes(String candidateId) {
        return new ArrayList<>(notes.getOrDefault(candidateId, List.of()));
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
        if ("POST".equals(method) && path.contains("/activity_feed/notes")) {
            return 201;
        }
        return 200; // jobs verify / candidates pull default
    }

    private synchronized String bodyFor(String method, String path, int status) {
        if (status >= 400) {
            return "{\"errors\":[{\"message\":\"error\"}]}";
        }
        if ("POST".equals(method) && path.contains("/activity_feed/notes")) {
            return "{\"id\":" + noteIdSeq.incrementAndGet() + "}";
        }
        if ("GET".equals(method) && path.contains("/v1/jobs")) {
            return "[]";
        }
        if ("GET".equals(method) && path.contains("/v1/candidates")) {
            return candidatesBody();
        }
        return "{}";
    }

    private synchronized String candidatesBody() {
        ArrayNode arr = MAPPER.createArrayNode();
        int idSeq = 1;
        for (SeededCandidate c : candidates.values()) {
            ObjectNode cand = arr.addObject();
            cand.put("id", idSeq++);
            cand.put("first_name", c.firstName());
            cand.put("last_name", c.lastName());

            ArrayNode emails = cand.putArray("email_addresses");
            ObjectNode em = emails.addObject();
            em.put("value", c.email());
            em.put("type", "personal");

            ArrayNode phones = cand.putArray("phone_numbers");
            ObjectNode ph = phones.addObject();
            ph.put("value", c.phone());
            ph.put("type", "mobile");

            // Seeded fields the client MUST NEVER parse (FR-029 / SC-005 non-circular).
            ArrayNode attachments = cand.putArray("attachments");
            ObjectNode att = attachments.addObject();
            att.put("filename", "resume_SENTINEL.pdf");
            att.put("url", "https://example.invalid/SENTINEL_ATTACHMENT");
            ArrayNode customFields = cand.putArray("custom_fields");
            ObjectNode cf = customFields.addObject();
            cf.put("name", "salary_expectation");
            cf.put("value", "SENTINEL_CUSTOM");
            ObjectNode eeoc = cand.putObject("eeoc");
            eeoc.put("gender", "SENTINEL_EEOC");
            eeoc.put("race", "SENTINEL_EEOC");
            ArrayNode tags = cand.putArray("tags");
            tags.add("SENTINEL_TAG");

            ArrayNode applications = cand.putArray("applications");
            ObjectNode app = applications.addObject();
            // The external ref is "gh_app:<application.id>"; seed an application id derived from externalRef.
            app.put("id", c.externalRef());
            ArrayNode jobs = app.putArray("jobs");
            ObjectNode job = jobs.addObject();
            job.put("id", c.jobId());
            job.put("name", c.jobTitle());
            ObjectNode stage = app.putObject("current_stage");
            stage.put("name", c.stage());
        }
        try {
            return MAPPER.writeValueAsString(arr);
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
