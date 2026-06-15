package com.cadence.calendar;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A tiny in-test OAuth provider stub backed by the JDK {@link HttpServer} (zero extra dependency,
 * no WireMock fat-jar classpath conflicts). Stubs are matched by path + a request-body substring
 * (newest stub wins); requests are recorded for verification. Thread-safe for the concurrent-refresh
 * test.
 */
final class StubProvider {

    private record Stub(String path, String bodyContains, int status, String body) {}
    private record Recorded(String path, String body) {}

    private final HttpServer server;
    private final List<Stub> stubs = new ArrayList<>();
    private final List<Recorded> requests = new ArrayList<>();
    private volatile CountDownLatch gateArrivals;
    private volatile CountDownLatch gateRelease;

    StubProvider() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // Cached pool so N gated requests can all block (arrive) concurrently without starving a fixed pool.
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (this) {
                requests.add(new Recorded(path, body));
            }
            awaitGate(); // genuine contention for the concurrency test (no vacuous pass)
            Stub match = match(path, body);
            int status = match == null ? 404 : match.status();
            byte[] out = (match == null ? "" : match.body()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    synchronized void stub(String path, String bodyContains, int status, String body) {
        stubs.add(0, new Stub(path, bodyContains, status, body)); // newest first -> wins on re-stub
    }

    synchronized void reset() {
        stubs.clear();
        requests.clear();
        gateArrivals = null;
        gateRelease = null;
    }

    /** Make the next {@code expected} requests all block until they have ALL arrived, then release together. */
    void gate(int expected) {
        gateArrivals = new CountDownLatch(expected);
        gateRelease = new CountDownLatch(1);
    }

    private void awaitGate() {
        CountDownLatch arrivals = gateArrivals;
        CountDownLatch release = gateRelease;
        if (arrivals == null || release == null) {
            return;
        }
        arrivals.countDown();
        if (arrivals.getCount() == 0) {
            release.countDown(); // last arrival opens the gate for everyone
        }
        try {
            release.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    synchronized long count(String path, String bodyContains) {
        return requests.stream()
            .filter(r -> r.path().equals(path) && r.body().contains(bodyContains))
            .count();
    }

    private synchronized Stub match(String path, String body) {
        for (Stub s : stubs) {
            if (s.path().equals(path) && (s.bodyContains().isEmpty() || body.contains(s.bodyContains()))) {
                return s;
            }
        }
        return null;
    }
}
