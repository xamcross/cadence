package com.cadence.pipeline;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cadence.api.PipelineDtos.BulkAction;
import com.cadence.api.PipelineDtos.BulkRequest;
import com.cadence.api.PipelineDtos.BulkResponse;
import com.cadence.api.PipelineDtos.PipelinePage;
import com.cadence.api.PipelineDtos.PipelineSort;
import com.cadence.api.PipelineDtos.PipelineStatusFilter;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.ErasureState;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.Role;
import com.cadence.service.PipelineBulkService;
import com.cadence.service.PipelineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F51 T046 (SC-008/FR-024): the decrypted candidate name and stage label (the new PII vectors this feature surfaces)
 * are NEVER logged at any level, NEVER returned in a bulk-result body, and NEVER persisted on a dispatch row —
 * across compose + timeline + bulk. The stage sentinel is seeded into ALL THREE stage-source fields
 * ({@code statusStage} / {@code atsStageLabel} / {@code importStageLabel}) so the full fallback chain is exercised
 * (a future leak from the ats/import fields would otherwise pass undetected).
 */
class PipelineLogPiiScanTest extends PipelineItBase {

    private static final String NAME_SENTINEL = "SENTINELF51NAME_zz9";
    private static final String STAGE_SENTINEL = "SENTINELF51STAGE_zz9";

    @Autowired PipelineBulkService bulkService;
    private final ObjectMapper json = new ObjectMapper();

    /** A contactable ACTIVE candidate carrying the stage sentinel in EXACTLY one stage-source field. */
    private void seedStageVariant(String id, String stageField) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setWorkspaceId(WS);
        c.setName(NAME_SENTINEL + "_" + id);
        c.setEmail(id + "@x.test");
        c.setLawfulBasis(LawfulBasis.CONSENT);
        c.setBasisRecordedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setErasureState(ErasureState.ACTIVE);
        c.setStatusOutcome(CandidateStatusOutcome.IN_PROGRESS);
        c.setLastContactAt(NOW.minusSeconds(86400));
        c.setCreatedAt(NOW.minusSeconds(86400));
        switch (stageField) {
            case "status" -> c.setStatusStage(STAGE_SENTINEL);
            case "ats" -> c.setAtsStageLabel(STAGE_SENTINEL);
            case "import" -> c.setImportStageLabel(STAGE_SENTINEL);
            default -> throw new IllegalArgumentException(stageField);
        }
        mongoTemplate.save(c);
    }

    @Test
    void composeBulkTimeline_neverLeakNameOrStage_acrossAllThreeStageFields() throws Exception {
        configuredWorkspace();
        seedStageVariant("c1", "status");
        seedStageVariant("c2", "ats");
        seedStageVariant("c3", "import");

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger cad = (Logger) LoggerFactory.getLogger("com.cadence");
        Level old = cad.getLevel();
        cad.setLevel(Level.TRACE);
        cad.addAppender(appender);
        appender.start();
        try {
            // compose — sanity: all three stage-fallback levels materialise the decrypted sentinel (so absence is
            // meaningful), and the name is materialised on every row.
            PipelinePage page = pipelineService.list(WS, "rec", Role.RECRUITER,
                new PipelineService.Filters(PipelineStatusFilter.ACTIVE, null, null, null, null),
                PipelineSort.RECENT, 0, 50);
            assertThat(page.rows()).hasSize(3);
            assertThat(page.rows()).allSatisfy(r -> assertThat(r.stage()).isEqualTo(STAGE_SENTINEL));
            assertThat(page.rows()).allSatisfy(r -> assertThat(r.name()).contains(NAME_SENTINEL));

            // timeline
            for (String id : List.of("c1", "c2", "c3")) {
                pipelineService.timeline(WS, "rec", Role.RECRUITER, id);
            }

            // bulk — the result body must carry ids/enums/coarse-reason only.
            BulkResponse bulk = bulkService.execute(WS, "rec",
                new BulkRequest(BulkAction.SEND_UPDATE_EMAIL, List.of("c1", "c2", "c3"),
                    null, null, null, null, null),
                "127.0.0.1");
            String bulkJson = json.writeValueAsString(bulk);
            assertThat(bulkJson).doesNotContain(NAME_SENTINEL).doesNotContain(STAGE_SENTINEL);

            // logs across compose + timeline + bulk
            for (ILoggingEvent e : appender.list) {
                assertThat(e.getFormattedMessage()).doesNotContain(NAME_SENTINEL).doesNotContain(STAGE_SENTINEL);
            }

            // dispatch rows persist ids/enums only — never a name/stage sentinel.
            for (EmailDispatch d : mongoTemplate.find(new Query(), EmailDispatch.class)) {
                String fields = String.valueOf(d.getStageKey()) + d.getRenderContextRef() + d.getCandidateId();
                assertThat(fields).doesNotContain(NAME_SENTINEL).doesNotContain(STAGE_SENTINEL);
            }
        } finally {
            appender.stop();
            cad.detachAppender(appender);
            cad.setLevel(old);
        }
    }
}
