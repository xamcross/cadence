package com.cadence.status;

import com.cadence.api.CandidateStatusDtos.DisplayState;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.service.CandidateStatusService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F30 T012 (SC-016/SC-013): the displayState precedence matrix (TERMINAL &gt; PAST_DATE &gt; PUBLISHED &gt;
 * UNDER_REVIEW) and the past-date boundary computed against a controlled "today". Pure unit — drives the
 * package-visible static {@code CandidateStatusService.resolveDisplayState(Candidate, LocalDate)} via
 * reflection (so no Spring/Mongo). Includes the conflict cases (terminal+past, under-review+past).
 */
class DisplayStateResolverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 17);

    private static DisplayState resolve(Candidate c, LocalDate today) {
        try {
            Method m = CandidateStatusService.class.getDeclaredMethod(
                "resolveDisplayState", Candidate.class, LocalDate.class);
            m.setAccessible(true);
            return (DisplayState) m.invoke(null, c, today);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Candidate published(CandidateStatusOutcome outcome, LocalDate expected) {
        Candidate c = new Candidate();
        c.setStatusPublishedAt(Instant.parse("2026-06-01T00:00:00Z"));
        c.setStatusOutcome(outcome);
        c.setStatusExpectedDate(expected);
        c.setStatusStage("Onsite");
        c.setStatusNextStep("Collecting feedback");
        return c;
    }

    @Test
    void neverPublished_isUnderReview() {
        Candidate c = new Candidate(); // statusPublishedAt == null
        assertThat(resolve(c, TODAY)).isEqualTo(DisplayState.UNDER_REVIEW);
    }

    @Test
    void underReviewWinsEvenIfAnExpectedDateSomehowSitInThePast() {
        // statusPublishedAt == null dominates everything, even a stray past date.
        Candidate c = new Candidate();
        c.setStatusExpectedDate(TODAY.minusDays(5));
        c.setStatusOutcome(CandidateStatusOutcome.IN_PROGRESS);
        assertThat(resolve(c, TODAY)).isEqualTo(DisplayState.UNDER_REVIEW);
    }

    @Test
    void terminalOffer_isTerminal() {
        assertThat(resolve(published(CandidateStatusOutcome.COMPLETE_OFFER, null), TODAY))
            .isEqualTo(DisplayState.TERMINAL);
    }

    @Test
    void terminalRejected_isTerminal() {
        assertThat(resolve(published(CandidateStatusOutcome.COMPLETE_REJECTED, null), TODAY))
            .isEqualTo(DisplayState.TERMINAL);
    }

    @Test
    void terminalWinsOverPastDate() {
        // A terminal outcome whose (stale) expected date is in the past still renders TERMINAL, not PAST_DATE.
        Candidate c = published(CandidateStatusOutcome.COMPLETE_REJECTED, TODAY.minusDays(10));
        assertThat(resolve(c, TODAY)).isEqualTo(DisplayState.TERMINAL);
    }

    @Test
    void inProgressFutureDate_isPublished() {
        assertThat(resolve(published(CandidateStatusOutcome.IN_PROGRESS, TODAY.plusDays(2)), TODAY))
            .isEqualTo(DisplayState.PUBLISHED);
    }

    @Test
    void inProgressTodayDate_isPublished() {
        // today-or-future renders normally (strictly-before is the PAST_DATE boundary).
        assertThat(resolve(published(CandidateStatusOutcome.IN_PROGRESS, TODAY), TODAY))
            .isEqualTo(DisplayState.PUBLISHED);
    }

    @Test
    void inProgressPastDate_isPastDate() {
        assertThat(resolve(published(CandidateStatusOutcome.IN_PROGRESS, TODAY.minusDays(1)), TODAY))
            .isEqualTo(DisplayState.PAST_DATE);
    }

    @Test
    void inProgressNullDate_isPublished_notPastDate() {
        // Defensive: an in-progress with no date (shouldn't pass validation) is not treated as past.
        assertThat(resolve(published(CandidateStatusOutcome.IN_PROGRESS, null), TODAY))
            .isEqualTo(DisplayState.PUBLISHED);
    }
}
