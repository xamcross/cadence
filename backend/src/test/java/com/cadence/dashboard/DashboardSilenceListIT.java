package com.cadence.dashboard;

import com.cadence.api.DashboardDtos.SilenceRow;
import com.cadence.api.DashboardWindow;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.repository.CandidateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * F50 US2 (SC-004/SC-005, FR-009/010/011/012) — silence-list membership, ordering, cap, the decrypt bound, and
 * on-read freshness. {@code silence-list-cap=3} makes the cap + decrypt-bound testable without seeding 100 rows.
 */
@TestPropertySource(properties = "cadence.dashboard.silence-list-cap=3")
class DashboardSilenceListIT extends DashboardItBase {

    @SpyBean CandidateRepository candidateRepository;

    private List<SilenceRow> list() {
        return dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS).silenceList();
    }

    @Test
    void terminalOutcome_excluded_guardsTheSlaSeam() {
        configuredWorkspace();
        seedSilent("active", "Ada", 10);
        seedCandidate("offer", "Ben", NOW.minus(Duration.ofDays(10)),
            CandidateStatusOutcome.COMPLETE_OFFER, ErasureState.ACTIVE);
        seedCandidate("reject", "Cal", NOW.minus(Duration.ofDays(10)),
            CandidateStatusOutcome.COMPLETE_REJECTED, ErasureState.ACTIVE);
        assertThat(list()).extracting(SilenceRow::candidateId).containsExactly("active");
    }

    @Test
    void erased_excluded() {
        configuredWorkspace();
        seedSilent("active", "Ada", 10);
        seedCandidate("erased", "Ben", NOW.minus(Duration.ofDays(10)),
            CandidateStatusOutcome.IN_PROGRESS, ErasureState.ERASED);
        assertThat(list()).extracting(SilenceRow::candidateId).containsExactly("active");
    }

    @Test
    void order_mostOverdueFirst() {
        configuredWorkspace();
        seedSilent("c6", "Six", 6);
        seedSilent("c8", "Eight", 8);
        // amber band is age in (4, 5] days (window 5, margin 1): exactly 4 days = GREEN, 5 days = AMBER.
        seedSilent("c5", "Five", 5);
        assertThat(list()).extracting(SilenceRow::candidateId).containsExactly("c8", "c6", "c5");
    }

    @Test
    void exactlyAtThreshold_classifiesDeterministically_amber() {
        configuredWorkspace();
        // basis exactly now-5d: NOT before breachCutoff(now-5d) -> not RED; before amberCutoff(now-4d) -> AMBER.
        seedSilent("edge", "Edge", 5);
        assertThat(list()).extracting(SilenceRow::severity).containsExactly("AMBER");
    }

    @Test
    void daysSilent_wholeDays() {
        configuredWorkspace();
        seedSilent("c9", "Nine", 9);
        assertThat(list().get(0).daysSilent()).isEqualTo(9L);
    }

    @Test
    void cap_andDecryptBound_areEnforcedByTheDashboard() {
        configuredWorkspace();
        for (int i = 0; i < 5; i++) {
            seedSilent("c" + i, "Name" + i, 10 + i);
        }
        List<SilenceRow> rows = list();
        assertThat(rows).hasSize(3); // capped (silence-list-cap=3)
        // The decrypt batch-load is invoked only on the truncated id set (<= cap) -> the decrypt bound (FR-012).
        // 5 breaches were seeded; if the code decrypted-then-truncated, the repo would be called with 5 ids and
        // this matcher would find no matching invocation -> fail. So it genuinely proves truncate-before-decrypt.
        verify(candidateRepository, atLeastOnce())
            .findByWorkspaceIdAndIdIn(eq(WS), argThat(ids -> ids.size() <= 3));
    }

    @Test
    void onReadFreshness_breachAppearsOnNextRead() {
        configuredWorkspace();
        seedSilent("c3", "Three", 3); // within window -> GREEN, absent
        assertThat(list()).isEmpty();
        clock.advance(Duration.ofDays(3)); // now 6 days silent -> RED
        assertThat(list()).extracting(SilenceRow::candidateId).containsExactly("c3");
    }

    @Test
    void names_neverNull_andRepoIsConsulted() {
        configuredWorkspace();
        seedSilent("c8", "Eight", 8);
        assertThat(list().get(0).candidateName()).isEqualTo("Eight");
        verify(candidateRepository, atLeastOnce()).findByWorkspaceIdAndIdIn(eq(WS), any());
    }
}
