package com.cadence.interest;

import com.cadence.service.InterestProperties;
import com.cadence.service.InterestRateLimiter;
import com.cadence.service.InterestRequestService;
import com.cadence.service.InterestRequestService.SubmitCommand;
import com.cadence.security.PiiCrypto;
import com.cadence.security.TokenHasher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T013: a pure unit test for the bot heuristic (honeypot + sub-minFill) and the best-effort real-client-IP
 * resolution. The honeypot/min-fill paths are a NEUTRAL accept with NO row (no repo insert). The IP resolver
 * prefers CF-Connecting-IP, then the leftmost validated X-Forwarded-For, then getRemoteAddr() — INCLUDING a
 * spoofed-header case documenting that layer-1 keying is best-effort/not security-relied-upon (the durable guard
 * is the per-workspace DB ceiling).
 */
class InterestBotHeuristicTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-23T12:00:00Z"), ZoneId.of("UTC"));

    private InterestProperties props() {
        InterestProperties p = new InterestProperties();
        p.setDefaultWorkspaceId("ws1");
        return p; // defaults: minFillMillis=1500
    }

    @Test
    void honeypotFilled_neutralAccept_noRepoInsert() {
        var repo = mock(com.cadence.repository.InterestRequestRepository.class);
        var rl = mock(InterestRateLimiter.class);
        var svc = new InterestRequestService(repo, mock(org.springframework.data.mongodb.core.MongoTemplate.class),
            mock(com.cadence.service.InvitationService.class), rl,
            mock(com.cadence.service.RecruiterNotificationService.class),
            mock(PiiCrypto.class), props(), FIXED, new com.cadence.service.CsvInjectionEscaper());

        svc.submit(new SubmitCommand("Bot", "bot@example.com", null, null, "http://spam", null), "1.1.1.1");

        verify(repo, never()).insert(org.mockito.ArgumentMatchers.any(com.cadence.domain.InterestRequest.class));
        verify(rl, never()).tryAcquire(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void subMinFill_neutralAccept_noRepoInsert() {
        var repo = mock(com.cadence.repository.InterestRequestRepository.class);
        var svc = new InterestRequestService(repo, mock(org.springframework.data.mongodb.core.MongoTemplate.class),
            mock(com.cadence.service.InvitationService.class), mock(InterestRateLimiter.class),
            mock(com.cadence.service.RecruiterNotificationService.class),
            mock(PiiCrypto.class), props(), FIXED, new com.cadence.service.CsvInjectionEscaper());

        // Rendered 100 ms ago -> below the 1500 ms min-fill -> treated as a bot.
        long renderedAt = Instant.now(FIXED).toEpochMilli() - 100L;
        svc.submit(new SubmitCommand("Bot", "bot@example.com", null, null, "", renderedAt), "1.1.1.1");
        verify(repo, never()).insert(org.mockito.ArgumentMatchers.any(com.cadence.domain.InterestRequest.class));
    }

    @Test
    void humanFill_proceedsPastHeuristic() {
        var repo = mock(com.cadence.repository.InterestRequestRepository.class);
        var rl = mock(InterestRateLimiter.class);
        when(rl.tryAcquire(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        var crypto = mock(PiiCrypto.class);
        when(crypto.emailHash(org.mockito.ArgumentMatchers.anyString())).thenReturn("h");
        var svc = new InterestRequestService(repo, mock(org.springframework.data.mongodb.core.MongoTemplate.class),
            mock(com.cadence.service.InvitationService.class), rl,
            mock(com.cadence.service.RecruiterNotificationService.class), crypto, props(), FIXED,
            new com.cadence.service.CsvInjectionEscaper());

        long renderedAt = Instant.now(FIXED).toEpochMilli() - 5000L; // 5 s ago -> human
        svc.submit(new SubmitCommand("Human", "h@example.com", null, null, "", renderedAt), "1.1.1.1");
        verify(repo, times(1)).insert(org.mockito.ArgumentMatchers.any(com.cadence.domain.InterestRequest.class));
    }

    // ---- IP resolution (best-effort) ----

    private InterestRateLimiter limiter() {
        // Use a real TokenHasher with dev peppers so hashIp works; we only exercise resolveClientIp here.
        var auth = new com.cadence.config.AuthProperties();
        auth.getCrypto().setTokenPepper("dev-token");
        auth.getCrypto().setIpPepper("dev-ip");
        auth.getCrypto().setPiiKey("dev-key");
        auth.getCrypto().setPiiPepper("dev-pepper");
        return new InterestRateLimiter(new TokenHasher(auth), props(), FIXED);
    }

    @Test
    void resolveClientIp_prefersCfConnectingIp() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("CF-Connecting-IP")).thenReturn("203.0.113.7");
        when(req.getHeader("X-Forwarded-For")).thenReturn("198.51.100.9, 203.0.113.7");
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        assertThat(limiter().resolveClientIp(req)).isEqualTo("203.0.113.7");
    }

    @Test
    void resolveClientIp_fallsBackToValidatedXffThenRemoteAddr() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("CF-Connecting-IP")).thenReturn(null);
        when(req.getHeader("X-Forwarded-For")).thenReturn("198.51.100.9, 203.0.113.7");
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        assertThat(limiter().resolveClientIp(req)).isEqualTo("198.51.100.9");

        HttpServletRequest noHeaders = mock(HttpServletRequest.class);
        when(noHeaders.getRemoteAddr()).thenReturn("10.0.0.1");
        assertThat(limiter().resolveClientIp(noHeaders)).isEqualTo("10.0.0.1");
    }

    @Test
    void resolveClientIp_spoofedGarbageHeader_isRejected_bestEffortNotSecurityRelied() {
        // A spoofed/garbage XFF (header injection attempt) is rejected by the conservative IP validation and
        // falls through to getRemoteAddr(). This documents that layer-1 keying is best-effort: an attacker who
        // CAN inject a valid-looking IP simply rotates the layer-1 key; the durable guard is the DB ceiling.
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("CF-Connecting-IP")).thenReturn("not an ip\r\nInjected: header");
        when(req.getHeader("X-Forwarded-For")).thenReturn("<script>alert(1)</script>");
        when(req.getRemoteAddr()).thenReturn("10.0.0.5");
        assertThat(limiter().resolveClientIp(req)).isEqualTo("10.0.0.5");
    }
}
