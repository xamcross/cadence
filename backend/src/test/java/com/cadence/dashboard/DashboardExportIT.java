package com.cadence.dashboard;

import com.cadence.api.DashboardDtos.DashboardSnapshot;
import com.cadence.api.DashboardWindow;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F50 US3 (SC-006/SC-012, FR-017/018/019/019b) — CSV shape, injection-neutralisation, non-terminating rate
 * rounding, erased exclusion, export==screen identity, and the single value-free audit event.
 */
class DashboardExportIT extends DashboardItBase {

    @Test
    void csvCell_injectionNeutralised() {
        configuredWorkspace();
        seedCandidate("evil", "=cmd()", NOW.minus(Duration.ofDays(10)),
            com.cadence.domain.CandidateStatusOutcome.IN_PROGRESS, com.cadence.domain.ErasureState.ACTIVE);
        DashboardSnapshot snap = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS);
        String csv = dashboardService.renderCsv(snap);
        // The escaper prefixes a single quote so a spreadsheet treats the cell as literal text.
        assertThat(csv).contains("'=cmd()");
        assertThat(csv).doesNotContain(",=cmd()"); // never a bare formula cell
    }

    @Test
    void noShowPercent_nonTerminating_roundedHalfUpOneDecimal() {
        Instant past = NOW.minus(Duration.ofDays(2));
        for (int i = 0; i < 7; i++) {
            seedBooked("ns" + i, past, past, past, i < 2 ? past : null); // 2 / 7 = 0.2857...
        }
        String csv = dashboardService.renderCsv(dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS));
        assertThat(csv).contains("28.6%");
        assertThat(csv).contains("2 of 7");
    }

    @Test
    void csvMetricCells_matchSnapshotFigures() {
        // FR-017 export==screen: the CSV metric cells must equal the snapshot the screen would show.
        Instant pastStart = NOW.minus(Duration.ofDays(2));
        Instant pastBooked = NOW.minus(Duration.ofDays(3));
        Instant sent = pastBooked.minus(Duration.ofHours(5)); // each takes 5h to book -> median 5.0h
        for (int i = 0; i < 10; i++) {
            seedBooked("ns" + i, sent, pastBooked, pastStart, i < 2 ? pastStart : null); // 2/10 no-show
        }
        DashboardSnapshot snap = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS);
        String csv = dashboardService.renderCsv(snap);
        assertThat(snap.timeToSchedule().medianHours()).isEqualTo(5.0);
        assertThat(snap.noShow().rate()).isEqualTo(0.2);
        assertThat(csv).contains("5.0");    // median hours cell
        assertThat(csv).contains("20.0%");  // no-show rate cell == snapshot figure
        assertThat(csv).contains("2 of 10");
    }

    @Test
    void erasedCandidate_absentFromExport() {
        configuredWorkspace();
        seedSilent("active", "Ada", 10);
        seedCandidate("erased", "SHOULDNOTAPPEAR", NOW.minus(Duration.ofDays(10)),
            com.cadence.domain.CandidateStatusOutcome.IN_PROGRESS, com.cadence.domain.ErasureState.ERASED);
        String csv = dashboardService.renderCsv(dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS));
        assertThat(csv).contains("Ada");
        assertThat(csv).doesNotContain("SHOULDNOTAPPEAR");
    }

    @Test
    void exportRowCount_equalsScreenSilenceLength() {
        configuredWorkspace();
        seedSilent("c6", "Six", 6);
        seedSilent("c8", "Eight", 8);
        DashboardSnapshot snap = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS);
        String csv = dashboardService.renderCsv(snap);
        long silenceRows = csv.lines().filter(l -> l.startsWith("Silence list,")).count();
        assertThat(silenceRows).isEqualTo(snap.silenceList().size());
    }

    @Test
    void export_emitsExactlyOneValueFreeAuditEvent_andAttachmentHeader() throws Exception {
        configuredWorkspace();
        seedSilent("c8", "SECRETNAME", 8);
        Member admin = member("admin@x.test", Role.ADMIN);
        mvc.perform(get("/api/internal/dashboard/export?window=LAST_30_DAYS").cookie(cookie(admin)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("attachment")));
        var events = mongoTemplate.find(
            Query.query(Criteria.where("eventType").is(AuthEventType.DASHBOARD_EXPORTED)), AuthAuditEvent.class);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOutcome()).isEqualTo("window=LAST_30_DAYS;rows=1");
        assertThat(events.get(0).getOutcome()).doesNotContain("SECRETNAME");
    }
}
