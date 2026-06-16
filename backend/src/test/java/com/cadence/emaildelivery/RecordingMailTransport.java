package com.cadence.emaildelivery;

import com.cadence.integration.MailTransport;
import com.cadence.integration.OutboundEmail;
import com.cadence.integration.SendOutcome;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test {@link MailTransport} (F22, T018 — the F10/F11 {@code StubGoogleCalendar} precedent). Records every
 * {@link OutboundEmail} the dispatch/transport path produces (the exactly-once + no-PII assertions are made
 * at this sink, not on the row). Supports:
 *
 * <ul>
 *   <li>an injectable per-call {@link SendOutcome} sequence (incl. transient failures) — {@link #enqueueOutcome};</li>
 *   <li>{@link #sentCount()} — the number of <em>accepted</em> transmits;</li>
 *   <li>a {@link #gate(int)} {@code CountDownLatch} that blocks {@link #transmit} until {@code n}
 *       concurrent calls have arrived, for non-vacuous concurrency tests;</li>
 *   <li>{@link #reset()} for {@code @BeforeEach} isolation.</li>
 * </ul>
 */
public class RecordingMailTransport implements MailTransport {

    private final ConcurrentLinkedQueue<OutboundEmail> messages = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SendOutcome> scriptedOutcomes = new ConcurrentLinkedQueue<>();
    private final AtomicInteger acceptedCount = new AtomicInteger();

    private volatile CountDownLatch arrivalGate;   // counts down on each transmit arrival
    private volatile CountDownLatch releaseGate;    // released once n have arrived

    @Override
    public SendOutcome transmit(OutboundEmail message) {
        CountDownLatch arrival = arrivalGate;
        CountDownLatch release = releaseGate;
        if (arrival != null) {
            arrival.countDown();
            try {
                // Block until n concurrent calls have all arrived (proves a real race, not serial).
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        messages.add(message);
        SendOutcome scripted = scriptedOutcomes.poll();
        SendOutcome outcome = scripted != null
            ? scripted
            : SendOutcome.accepted("rec-" + UUID.randomUUID());
        if (outcome.accepted()) {
            acceptedCount.incrementAndGet();
        }
        return outcome;
    }

    /** Script the outcome for the next (n-th) transmit; calls beyond the script default to accepted. */
    public void enqueueOutcome(SendOutcome outcome) {
        scriptedOutcomes.add(outcome);
    }

    /** Arm a latch that blocks every transmit until {@code n} concurrent calls have arrived. */
    public void gate(int n) {
        this.arrivalGate = new CountDownLatch(n);
        this.releaseGate = new CountDownLatch(1);
        // Release the waiters once all n have arrived, on a watcher thread.
        CountDownLatch arrival = this.arrivalGate;
        CountDownLatch release = this.releaseGate;
        Thread watcher = new Thread(() -> {
            try {
                arrival.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                release.countDown();
            }
        }, "recording-mail-gate-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** Number of transmits that were accepted (the exactly-once assertion point). */
    public int sentCount() {
        return acceptedCount.get();
    }

    /** Total transmit calls (accepted + failed). */
    public int totalCalls() {
        return messages.size();
    }

    public List<OutboundEmail> messages() {
        return List.copyOf(messages);
    }

    /** Number of transmits addressed to a specific recipient (e.g. a candidate vs the ops address). */
    public long callsTo(String address) {
        return messages.stream().filter(m -> address.equals(m.toAddress())).count();
    }

    public void reset() {
        messages.clear();
        scriptedOutcomes.clear();
        acceptedCount.set(0);
        arrivalGate = null;
        releaseGate = null;
    }
}
