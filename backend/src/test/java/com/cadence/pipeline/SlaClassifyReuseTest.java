package com.cadence.pipeline;

import com.cadence.config.SlaProperties;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.domain.SlaState;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.service.SlaNudgeService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F51 T022 / FR-004 (anti-drift): the pipeline's public SLA seam {@code SlaNudgeService.classifyCandidate(cfg,
 * candidate, now)} MUST delegate to the SAME package-static {@code classify(...)} the dashboard/silence-list use,
 * with the SAME window + amber-margin inputs, so the pipeline SLA colour can never drift from the canonical
 * verdict. Pure unit (no Spring, no container): the service is constructed with nulls for every collaborator the
 * classification path does not touch (only {@link SlaProperties} is read), and the static is invoked via
 * reflection (the {@code SlaClassifierTest} precedent) to compute the independent expected value across a matrix
 * that spans GREEN/AMBER/RED, erased, terminal, null-last-contact, and the window-resolution edges.
 */
class SlaClassifyReuseTest {

    private static final int AMBER_MARGIN = 1;
    private static final int DEFAULT_WINDOW = 5;
    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    private static SlaNudgeService service() {
        SlaProperties props = new SlaProperties();
        props.setAmberMarginDays(AMBER_MARGIN);
        props.setDefaultWindowDays(DEFAULT_WINDOW);
        // Every collaborator below is unused by classifyCandidate -> null is safe (the constructor only assigns).
        return new SlaNudgeService(null, null, null, null, null, null, null, null, null, null, null, props, null);
    }

    private static Method staticClassify() throws Exception {
        Method m = Class.forName("com.cadence.service.SlaNudgeService").getDeclaredMethod(
            "classify", Instant.class, Instant.class, CandidateStatusOutcome.class, ErasureState.class,
            int.class, int.class, Instant.class);
        m.setAccessible(true);
        return m;
    }

    /** Replicate {@code effectiveWindowDays(cfg)}: null cfg or zero window -> default; else the configured window. */
    private static int effectiveWindow(WorkspaceConfig cfg) {
        int w = cfg == null ? 0 : cfg.getSlaSilenceWindowDays();
        return w > 0 ? w : DEFAULT_WINDOW;
    }

    private static Candidate candidate(Instant lastContactAt, Instant createdAt,
                                       CandidateStatusOutcome outcome, ErasureState erasureState) {
        Candidate c = new Candidate();
        c.setLastContactAt(lastContactAt);
        c.setCreatedAt(createdAt);
        c.setStatusOutcome(outcome);
        c.setErasureState(erasureState);
        return c;
    }

    private static WorkspaceConfig cfgWithWindow(int windowDays) {
        WorkspaceConfig cfg = new WorkspaceConfig();
        cfg.setSlaSilenceWindowDays(windowDays);
        return cfg;
    }

    @Test
    void classifyCandidate_matchesStaticClassify_acrossTheMatrix() throws Exception {
        SlaNudgeService svc = service();
        Method staticM = staticClassify();

        // The window-resolution edges the public seam must honour identically: configured, zero (-> default),
        // null cfg (-> default), and a wider configured window.
        List<WorkspaceConfig> configs = new ArrayList<>();
        configs.add(cfgWithWindow(5));
        configs.add(cfgWithWindow(0));   // resolves to DEFAULT_WINDOW
        configs.add(null);               // resolves to DEFAULT_WINDOW
        configs.add(cfgWithWindow(10));

        // Candidates spanning every classification outcome (relative to NOW), incl. the fail-safe paths.
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(NOW.minus(Duration.ofDays(1)), null, CandidateStatusOutcome.IN_PROGRESS, ErasureState.ACTIVE));        // well within
        candidates.add(candidate(NOW.minus(Duration.ofDays(4)).minusSeconds(1), null, CandidateStatusOutcome.IN_PROGRESS, ErasureState.ACTIVE)); // amber-edge (window 5)
        candidates.add(candidate(NOW.minus(Duration.ofDays(6)), null, CandidateStatusOutcome.IN_PROGRESS, ErasureState.ACTIVE));        // breached (window 5)
        candidates.add(candidate(NOW.minus(Duration.ofDays(8)), null, CandidateStatusOutcome.IN_PROGRESS, ErasureState.ACTIVE));        // within a window-10 cfg, breached a window-5
        candidates.add(candidate(NOW.minus(Duration.ofDays(30)), null, CandidateStatusOutcome.IN_PROGRESS, ErasureState.ERASED));       // erased -> never breach
        candidates.add(candidate(NOW.minus(Duration.ofDays(30)), null, CandidateStatusOutcome.COMPLETE_OFFER, ErasureState.ACTIVE));    // terminal -> never breach
        candidates.add(candidate(NOW.minus(Duration.ofDays(30)), null, CandidateStatusOutcome.COMPLETE_REJECTED, ErasureState.ACTIVE)); // terminal -> never breach
        candidates.add(candidate(null, NOW.minus(Duration.ofDays(7)), CandidateStatusOutcome.IN_PROGRESS, ErasureState.ACTIVE));        // null lastContact -> falls back to createdAt
        candidates.add(candidate(null, null, CandidateStatusOutcome.IN_PROGRESS, ErasureState.ACTIVE));                                 // no basis -> fail-safe GREEN

        boolean sawGreen = false, sawAmber = false, sawRed = false;
        for (WorkspaceConfig cfg : configs) {
            int window = effectiveWindow(cfg);
            for (Candidate c : candidates) {
                SlaState expected = (SlaState) staticM.invoke(null, c.getLastContactAt(), c.getCreatedAt(),
                    c.getStatusOutcome(), c.getErasureState(), window, AMBER_MARGIN, NOW);
                SlaState actual = svc.classifyCandidate(cfg, c, NOW);
                assertThat(actual)
                    .as("classifyCandidate must equal the static classify for window=%d candidate=%s", window, c.getLastContactAt())
                    .isEqualTo(expected);
                switch (actual) {
                    case GREEN -> sawGreen = true;
                    case AMBER -> sawAmber = true;
                    case RED -> sawRed = true;
                }
            }
        }
        // Non-vacuous: the matrix actually exercised all three colours (so the equality above is meaningful).
        assertThat(sawGreen).isTrue();
        assertThat(sawAmber).isTrue();
        assertThat(sawRed).isTrue();
    }

    @Test
    void classifyCandidate_usesEffectiveWindow_zeroAndNullCfgFallBackToDefault() throws Exception {
        SlaNudgeService svc = service();
        Method staticM = staticClassify();
        // Silent 6 days: RED under the default window (5), and the zero/null cfg must resolve to that same default.
        Candidate c = candidate(NOW.minus(Duration.ofDays(6)), null, CandidateStatusOutcome.IN_PROGRESS, ErasureState.ACTIVE);
        SlaState viaDefault = (SlaState) staticM.invoke(null, c.getLastContactAt(), c.getCreatedAt(),
            c.getStatusOutcome(), c.getErasureState(), DEFAULT_WINDOW, AMBER_MARGIN, NOW);
        assertThat(viaDefault).isEqualTo(SlaState.RED);
        assertThat(svc.classifyCandidate(cfgWithWindow(0), c, NOW)).isEqualTo(SlaState.RED);
        assertThat(svc.classifyCandidate(null, c, NOW)).isEqualTo(SlaState.RED);
        // The SAME candidate is only GREEN under a wide enough configured window (10) — proving the window is read.
        assertThat(svc.classifyCandidate(cfgWithWindow(10), c, NOW)).isEqualTo(SlaState.GREEN);
    }
}
