package com.cadence.dashboard;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cadence.api.DashboardDtos.DashboardSnapshot;
import com.cadence.api.DashboardWindow;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F50 T032 (SC-009) — the silence-list name (the only PII path) is NEVER logged, even at TRACE, but IS present
 * in the CSV cell (the deliberate egress). A sentinel name drives a render with TRACE logging captured.
 */
class DashboardLogPiiScanTest extends DashboardItBase {

    private static final String SENTINEL = "SENTINELF50NAME_zz9";

    @Test
    void render_neverLogsCandidateName_butCsvCarriesIt() {
        configuredWorkspace();
        seedSilent("c", SENTINEL, 9);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger cad = (Logger) LoggerFactory.getLogger("com.cadence");
        Level old = cad.getLevel();
        cad.setLevel(Level.TRACE);
        cad.addAppender(appender);
        appender.start();
        try {
            DashboardSnapshot snap = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS);
            String csv = dashboardService.renderCsv(snap);
            assertThat(csv).contains(SENTINEL); // present in the egress (correct)
            for (ILoggingEvent e : appender.list) {
                assertThat(e.getFormattedMessage()).doesNotContain(SENTINEL);
            }
        } finally {
            appender.stop();
            cad.detachAppender(appender);
            cad.setLevel(old);
        }
    }
}
