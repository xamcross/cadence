package com.cadence.ats;

import com.cadence.config.AtsProperties;
import com.cadence.integration.AtsApiRetry;
import com.cadence.integration.AtsCandidateRecord;
import com.cadence.integration.AtsFetchResult;
import com.cadence.integration.AtsProvider;
import com.cadence.integration.LeverAtsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F41 pure-unit (no Spring): {@link LeverAtsClient} parse-discipline / data minimization (FR-029). The stub seeds
 * links/tags/sources/origin/headline/archived with SENTINEL markers; the client reads ONLY the enumerated fields,
 * so none reach the normalized record (and the EEO endpoint is never called).
 */
class LeverAtsClientTest {

    private static final StubLever stub = new StubLever(); // JVM-lifetime; never stopped (dead-port footgun)

    private final AtsProperties props = props();
    private final LeverAtsClient client = new LeverAtsClient(new AtsApiRetry(props), props);

    private static AtsProperties props() {
        AtsProperties p = new AtsProperties();
        p.getLever().setBaseUrl(stub.baseUrl());
        p.setRetryBaseBackoff(Duration.ZERO);
        return p;
    }

    @AfterEach
    void clean() {
        stub.reset();
    }

    @Test
    void providerIsLever() {
        assertThat(client.provider()).isEqualTo(AtsProvider.LEVER);
    }

    @Test
    void parsesOnlyTheMinimizedFieldSet() {
        stub.addOpportunity("opp-1", "Jane Roe", "jane@example.com", "555-1", "job-9", "Staff Engineer", "Phone Screen");
        AtsFetchResult result = client.fetchCandidates("ws", "key", null);

        assertThat(result.records()).hasSize(1);
        AtsCandidateRecord r = result.records().get(0);
        assertThat(r.externalRef()).isEqualTo("opp-1");
        assertThat(r.name()).isEqualTo("Jane Roe");
        assertThat(r.email()).isEqualTo("jane@example.com");
        assertThat(r.phone()).isEqualTo("555-1");
        assertThat(r.externalJobId()).isEqualTo("job-9");
        assertThat(r.externalJobTitle()).isEqualTo("Staff Engineer");
        assertThat(r.stageLabel()).isEqualTo("Phone Screen");

        // Minimization: NO SENTINEL (links/tags/sources/origin/headline/archived) leaked into any record field.
        assertThat(r.toString()).doesNotContain("SENTINEL");
        // The EEO data lives on a separate endpoint that is never called.
        assertThat(stub.count("GET", "eeo")).isZero();
        assertThat(stub.count("GET", "/v1/opportunities")).isEqualTo(1);
    }

    @Test
    void skipsAnOpportunityWithNoId() {
        // (no seeding) -> empty data array -> zero records, no throw.
        AtsFetchResult result = client.fetchCandidates("ws", "key", null);
        assertThat(result.records()).isEmpty();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void backsOffAndRetriesOn429ThenSucceeds() {
        // FR-020/SC-004: Lever throttles (429) once, the client backs off (PT0S here) and retries to success.
        stub.addOpportunity("opp-1", "Jane Roe", "jane@example.com", "555-1", "job-9", "Eng", "Phone Screen");
        stub.program("GET", "/v1/opportunities", 429, 200);
        AtsFetchResult result = client.fetchCandidates("ws", "key", null);
        assertThat(result.records()).hasSize(1);
        assertThat(stub.count("GET", "/v1/opportunities")).isEqualTo(2); // one 429 + one 200
    }
}
