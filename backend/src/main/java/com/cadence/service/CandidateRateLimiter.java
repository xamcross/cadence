package com.cadence.service;

import com.cadence.config.SchedulingProperties;
import com.cadence.security.TokenHasher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory per-IP fixed-window rate limiter for the candidate scheduling endpoints (F13, research D8 /
 * FR-010). Keyed by {@link TokenHasher#hashIp} so no raw IP is held even in volatile memory. Single-instance
 * topology (constitution §IV) makes in-memory authoritative; state resets on restart / is per-instance during
 * a rolling deploy.
 *
 * <p><b>Advisory only</b>: this is NOT a correctness control. The no-double-book and no-oracle guarantees
 * rest on the DB unique-index claim and the 410/400 response design, never on this limiter (a reset window
 * relaxes brute-force defence briefly, but the 256-bit token is unguessable so no practical enumeration).
 */
@Component
public class CandidateRateLimiter {

    private final TokenHasher hasher;
    private final SchedulingProperties props;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public CandidateRateLimiter(TokenHasher hasher, SchedulingProperties props, Clock clock) {
        this.hasher = hasher;
        this.props = props;
        this.clock = clock;
    }

    /** @return true if the request is allowed; false if the per-minute limit for this IP is exceeded. */
    public boolean tryAcquire(String ip) {
        if (ip == null || ip.isBlank()) {
            return true; // no source to limit on — never block (advisory only)
        }
        long minute = Instant.now(clock).getEpochSecond() / 60L;
        String key = hasher.hashIp(ip);
        Window w = windows.compute(key, (k, cur) -> {
            if (cur == null || cur.minute != minute) {
                return new Window(minute);
            }
            return cur;
        });
        return w.count.incrementAndGet() <= props.getRateLimitPerMinute();
    }

    private static final class Window {
        final long minute;
        final AtomicInteger count = new AtomicInteger(0);
        Window(long minute) { this.minute = minute; }
    }
}
