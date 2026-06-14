package com.cadence.api;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only probe asserting that /api/candidate/** is NOT subject to the member-auth gate
 * (SC-003). F01 ships no real candidate endpoints; this exists only under the test profile so the
 * SessionGate test can prove a candidate path reaches a handler (non-401) without candidate-link
 * logic. Re-verified across real candidate paths as those features land.
 */
@RestController
@Profile("test")
public class CandidateProbeController {

    @GetMapping("/api/candidate/__probe")
    public String probe() {
        return "ok";
    }
}
