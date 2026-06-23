package com.cadence.interest;

import com.cadence.api.InterestExceptions;
import com.cadence.service.InterestRequestService.SubmitCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T012/SC-006: the layered flood defence. The per-source limiter blocks a single-source flood (layer 1); the
 * per-workspace DB-count ceiling blocks a flood of rotated-IP submissions while a normal single submit succeeds
 * (layer 2 — the durable guard). application-test.yml: max-per-ip = 3, max-per-workspace = 5.
 */
class InterestRateLimitIT extends InterestItBase {

    private SubmitCommand cmd(int i) {
        return new SubmitCommand("N" + i, "rl" + i + "@example.com", null, null, null, null);
    }

    @Test
    void singleSourceFlood_blockedByPerSourceLimiter() {
        // 3 allowed from one IP, then the 4th trips the per-source limiter.
        for (int i = 0; i < 3; i++) {
            interestService.submit(cmd(i), "5.5.5.5");
        }
        assertThatThrownBy(() -> interestService.submit(cmd(3), "5.5.5.5"))
            .isInstanceOf(InterestExceptions.RateLimitedException.class);
    }

    @Test
    void rotatedIpFlood_blockedByPerWorkspaceCeiling_normalSingleSucceeds() {
        // A single submit from a fresh IP succeeds.
        interestService.submit(cmd(100), "8.8.8.1");
        assertThat(interestRepo.findAll()).hasSize(1);

        // Rotate IPs to bypass layer 1; the per-workspace ceiling (5) becomes the gate. 4 more bring us to 5.
        for (int i = 0; i < 4; i++) {
            interestService.submit(cmd(i), "8.8.8." + (10 + i));
        }
        // The 6th (still a fresh IP) trips the durable per-workspace DB ceiling.
        assertThatThrownBy(() -> interestService.submit(cmd(200), "8.8.8.99"))
            .isInstanceOf(InterestExceptions.RateLimitedException.class);
    }
}
