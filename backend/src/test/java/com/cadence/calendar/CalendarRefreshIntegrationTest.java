package com.cadence.calendar;

import com.cadence.auth.AuthTestConfig;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** US2: transparent refresh, rotation, and concurrency-safety (T035). */
class CalendarRefreshIntegrationTest extends CalendarItBase {

    private void expireAccess() {
        clock.set(AuthTestConfig.FIXED_START.plusSeconds(3600)); // now >= expiry-skew -> stale
    }

    @Test
    void expiredAccess_validGrant_refreshesTransparently() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        expireAccess();
        stubRefresh(CalendarProvider.GOOGLE, "fresh-access", "refresh-google", 3600);

        String token = tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE);

        assertThat(token).isEqualTo("fresh-access"); // SC-004
        assertThat(wm.count(tokenPath(CalendarProvider.GOOGLE), "grant_type=refresh_token")).isEqualTo(1);
    }

    @Test
    void freshAccess_doesNotRefresh() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        // clock still at FIXED_START -> token fresh -> no refresh call
        String token = tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE);
        assertThat(token).isEqualTo("access-google");
        assertThat(wm.count(tokenPath(CalendarProvider.GOOGLE), "grant_type=refresh_token")).isZero();
    }

    @Test
    void rotatedRefreshToken_isPersisted_andUsedNext() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        expireAccess();
        stubRefresh(CalendarProvider.GOOGLE, "fresh-1", "refresh-rotated", 3600);
        tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE);

        CalendarConnection after = connectionRepo.findByWorkspaceIdAndMemberIdAndProvider(WS, m.getId(), CalendarProvider.GOOGLE).orElseThrow();
        assertThat(after.getRefreshToken()).isEqualTo("refresh-rotated"); // FR-013 persisted

        // Next refresh must POST the rotated token, not the original.
        clock.set(AuthTestConfig.FIXED_START.plusSeconds(7200));
        stubRefresh(CalendarProvider.GOOGLE, "fresh-2", "refresh-rotated", 3600);
        tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE);
        assertThat(wm.count(tokenPath(CalendarProvider.GOOGLE), "refresh-rotated")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void refreshResponseOmittingRefreshToken_preservesExisting() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        expireAccess();
        stubRefresh(CalendarProvider.GOOGLE, "fresh-access", null, 3600); // provider re-issues no refresh token

        tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE);

        CalendarConnection after = connectionRepo.findByWorkspaceIdAndMemberIdAndProvider(WS, m.getId(), CalendarProvider.GOOGLE).orElseThrow();
        assertThat(after.getRefreshToken()).isEqualTo("refresh-google"); // Security #8: preserved
    }

    @Test
    void concurrentRefresh_writesExactlyOnce() throws Exception {
        Member m = member("alex@x.com", Role.RECRUITER);
        CalendarConnection seed = connect(m, CalendarProvider.GOOGLE, "alex@example.com");
        long startVersion = seed.getTokenVersion();
        expireAccess();
        stubRefresh(CalendarProvider.GOOGLE, "fresh-shared", "refresh-google", 3600);

        int n = 24;
        wm.gate(n); // every refresh POST blocks until ALL n have arrived -> guarantees real contention
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<AtomicReference<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            AtomicReference<String> ref = new AtomicReference<>();
            results.add(ref);
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    ref.set(tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE));
                } catch (Exception e) {
                    ref.set("ERR:" + e.getClass().getSimpleName());
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        // The gate proved genuine contention: all n threads passed the freshness check and POSTed a
        // refresh (refresh-then-CAS, D5) — so the test is NOT vacuous.
        assertThat(wm.count(tokenPath(CalendarProvider.GOOGLE), "grant_type=refresh_token")).isEqualTo(n);
        // Exactly-one-WRITE despite n concurrent refreshers: version advanced by exactly 1 (the CAS
        // dedup), and every caller got a valid token (the winner's). No double-rotation/corruption.
        CalendarConnection finalState = connectionRepo.findById(seed.getId()).orElseThrow();
        assertThat(finalState.getTokenVersion()).isEqualTo(startVersion + 1);
        for (AtomicReference<String> r : results) {
            assertThat(r.get()).isEqualTo("fresh-shared");
        }
    }
}
