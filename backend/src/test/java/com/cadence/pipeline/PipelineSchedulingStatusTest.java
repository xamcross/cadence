package com.cadence.pipeline;

import com.cadence.api.PipelineDtos.PipelineSchedulingStatus;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.service.PipelineService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F51 T019: pure unit test of the FR-005 scheduling-status mapping (the single source of truth). No Spring / no DB.
 */
class PipelineSchedulingStatusTest {

    private static final Instant NOW = Instant.parse("2026-06-19T12:00:00Z");

    private static SchedulingRequest sr(SchedulingStatus status, Instant expiresAt, Instant noShowAt) {
        SchedulingRequest r = new SchedulingRequest();
        r.setStatus(status);
        r.setExpiresAt(expiresAt);
        r.setNoShowAt(noShowAt);
        return r;
    }

    @Test void nullRequest_noLinkSent() {
        assertThat(PipelineService.mapSchedulingStatus(null, NOW)).isEqualTo(PipelineSchedulingStatus.NO_LINK_SENT);
    }

    @Test void pendingNotExpired_linkSent() {
        assertThat(PipelineService.mapSchedulingStatus(
            sr(SchedulingStatus.PENDING_SELECTION, NOW.plus(Duration.ofHours(1)), null), NOW))
            .isEqualTo(PipelineSchedulingStatus.LINK_SENT);
    }

    @Test void pendingPastExpiry_expired() {
        assertThat(PipelineService.mapSchedulingStatus(
            sr(SchedulingStatus.PENDING_SELECTION, NOW.minus(Duration.ofHours(1)), null), NOW))
            .isEqualTo(PipelineSchedulingStatus.EXPIRED);
    }

    @Test void booking_slotPicked() {
        assertThat(PipelineService.mapSchedulingStatus(sr(SchedulingStatus.BOOKING, null, null), NOW))
            .isEqualTo(PipelineSchedulingStatus.SLOT_PICKED);
    }

    @Test void bookedNoNoShow_confirmed() {
        assertThat(PipelineService.mapSchedulingStatus(sr(SchedulingStatus.BOOKED, null, null), NOW))
            .isEqualTo(PipelineSchedulingStatus.CONFIRMED);
    }

    @Test void bookedWithNoShow_noShow() {
        assertThat(PipelineService.mapSchedulingStatus(sr(SchedulingStatus.BOOKED, null, NOW), NOW))
            .isEqualTo(PipelineSchedulingStatus.NO_SHOW);
    }

    @Test void rescheduled_rescheduled() {
        assertThat(PipelineService.mapSchedulingStatus(sr(SchedulingStatus.RESCHEDULED, null, null), NOW))
            .isEqualTo(PipelineSchedulingStatus.RESCHEDULED);
    }

    @Test void cancelled_cancelled() {
        assertThat(PipelineService.mapSchedulingStatus(sr(SchedulingStatus.CANCELLED, null, null), NOW))
            .isEqualTo(PipelineSchedulingStatus.CANCELLED);
        assertThat(PipelineService.mapSchedulingStatus(sr(SchedulingStatus.CANCELLING, null, null), NOW))
            .isEqualTo(PipelineSchedulingStatus.CANCELLED);
    }

    @Test void expired_expired() {
        assertThat(PipelineService.mapSchedulingStatus(sr(SchedulingStatus.EXPIRED, null, null), NOW))
            .isEqualTo(PipelineSchedulingStatus.EXPIRED);
    }

    @Test void supersededOrCleanup_noLinkSent() {
        assertThat(PipelineService.mapSchedulingStatus(sr(SchedulingStatus.SUPERSEDED, null, null), NOW))
            .isEqualTo(PipelineSchedulingStatus.NO_LINK_SENT);
        assertThat(PipelineService.mapSchedulingStatus(sr(SchedulingStatus.CLEANUP_INCOMPLETE, null, null), NOW))
            .isEqualTo(PipelineSchedulingStatus.NO_LINK_SENT);
    }
}
