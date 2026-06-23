package com.cadence.service;

import com.cadence.security.TokenHasher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F70 dedicated in-memory per-source fixed-window rate limiter for the public interest endpoint (R6). Distinct
 * from {@link CandidateRateLimiter} (which is per-minute and shared across candidate flows): this uses the
 * F70-configured {@code ipWindow}/{@code maxPerIpPerWindow}. Keyed by {@link TokenHasher#hashIp} so no raw IP is
 * held even in volatile memory. Single-instance topology (constitution SS-IV) makes in-memory authoritative.
 *
 * <p><b>Advisory only (layer 1).</b> The DURABLE anti-flood guard is the per-workspace DB-count ceiling in
 * {@link InterestRequestService} (layer 2). The real-client-IP resolution here is BEST-EFFORT: absent an
 * established trusted-proxy determination, a spoofed {@code X-Forwarded-For}/{@code CF-Connecting-IP} cannot be
 * distinguished from a genuine one, so layer-1 keying is not security-relied-upon (the DB ceiling is).
 */
@Component
public class InterestRateLimiter {

    private final TokenHasher hasher;
    private final InterestProperties props;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public InterestRateLimiter(TokenHasher hasher, InterestProperties props, Clock clock) {
        this.hasher = hasher;
        this.props = props;
        this.clock = clock;
    }

    /** @return true if allowed; false if this source exceeded {@code maxPerIpPerWindow} within {@code ipWindow}. */
    public boolean tryAcquire(String ip) {
        if (ip == null || ip.isBlank()) {
            return true; // no source to limit on — never block (advisory only)
        }
        long windowMillis = Math.max(1L, props.getIpWindow().toMillis());
        long bucket = Instant.now(clock).toEpochMilli() / windowMillis;
        String key = hasher.hashIp(ip);
        Window w = windows.compute(key, (k, cur) -> {
            if (cur == null || cur.bucket != bucket) {
                return new Window(bucket);
            }
            return cur;
        });
        return w.count.incrementAndGet() <= props.getMaxPerIpPerWindow();
    }

    /**
     * Resolve the real client IP, BEST-EFFORT (R6/T008). Prefer the Cloudflare {@code CF-Connecting-IP} header,
     * then the leftmost validated {@code X-Forwarded-For} hop, falling back to {@code getRemoteAddr()} (which
     * alone collapses to the proxy edge IP). NOTE: without an established trusted proxy these headers are
     * attacker-controllable, so this value is NOT security-relied-upon — it only improves the advisory layer-1
     * key quality behind the known CF edge; the durable guard is the per-workspace DB ceiling.
     */
    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String cf = request.getHeader("CF-Connecting-IP");
        if (isValidIp(cf)) {
            return cf.trim();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // The leftmost hop is the claimed original client. Validate it; a garbage value falls through.
            String first = xff.split(",", 2)[0].trim();
            if (isValidIp(first)) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    private static boolean isValidIp(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        if (v.isEmpty() || v.length() > 45) { // max IPv6 textual length
            return false;
        }
        // Conservative charset for an IPv4/IPv6 literal; rejects header-injection / hostname junk.
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || c == '.' || c == ':';
            if (!ok) {
                return false;
            }
        }
        return v.indexOf('.') >= 0 || v.indexOf(':') >= 0;
    }

    private static final class Window {
        final long bucket;
        final AtomicInteger count = new AtomicInteger(0);
        Window(long bucket) { this.bucket = bucket; }
    }
}
