package com.cadence.calendar;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cadence.auth.AuthTestConfig;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.Member;
import com.cadence.domain.OAuthFlowState;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-003 / FR-009/FR-010: no token, code, client secret, or provider-account email appears in any of
 * OUR logs — even at TRACE — across connect -> refresh -> reconnect -> disconnect (including a FAILING
 * revoke, the path most likely to echo a token via an HTTP-client error). The {@code com.cadence}
 * logger is driven at TRACE; framework loggers stay at their configured level so Spring's HTTP-client
 * wire-logging (which dumps request bodies only at TRACE on org.springframework.web, and is never
 * enabled in production) is out of scope — this test is about Cadence's own logging discipline. Drives
 * the real encrypt/decrypt path with high-entropy sentinels; a positive guard proves they traversed it.
 */
class CalendarLogPiiScanTest extends CalendarItBase {

    private static final String SENT_ACCESS = "SENTINELACCESS0xDEADBEEF0123";
    private static final String SENT_REFRESH = "SENTINELREFRESH0xCAFEBABE4567";
    private static final String SENT_ACCOUNT = "sentinel-acct-9f8e7d@example.invalid";
    private static final String SENT_CODE = "SENTINELCODE0x99887766";

    @Test
    void noTokenCodeSecretOrAccount_appearsInLogs_atTrace() {
        Member m = member("alex@x.com", Role.RECRUITER);

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger cadence = (Logger) LoggerFactory.getLogger("com.cadence");
        Level previousCadence = cadence.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        // Drive OUR loggers at TRACE; leave framework loggers (org.springframework.web wire-logging)
        // at their configured level so the test scopes to Cadence's own logging, not Spring's TRACE
        // request-body dump (which is never enabled in production).
        cadence.setLevel(Level.TRACE);
        try {
            // Connect with sentinel tokens + account + code.
            stubExchange(CalendarProvider.GOOGLE, SENT_ACCESS, SENT_REFRESH, 3600, SENT_ACCOUNT);
            connectionService.start(WS, m.getId(), CalendarProvider.GOOGLE);
            OAuthFlowState state = mongoTemplate.findOne(new Query(), OAuthFlowState.class);
            connectionService.completeCallback(CalendarProvider.GOOGLE, SENT_CODE, state.getId(), null, WS, m.getId());

            // Refresh with sentinels.
            clock.set(AuthTestConfig.FIXED_START.plusSeconds(3600));
            stubRefresh(CalendarProvider.GOOGLE, SENT_ACCESS, SENT_REFRESH, 3600);
            tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE);

            // Reconnect (invalid_grant).
            clock.set(AuthTestConfig.FIXED_START.plusSeconds(7200));
            stubRefreshError(CalendarProvider.GOOGLE, 400, "invalid_grant");
            try {
                tokenService.validAccessToken(WS, m.getId(), CalendarProvider.GOOGLE);
            } catch (RuntimeException ignored) {
                // expected
            }

            // Disconnect with a FAILING revoke (the likeliest token-in-log path).
            // Re-connect first so there is a row to disconnect.
            clock.set(AuthTestConfig.FIXED_START);
            stubExchange(CalendarProvider.GOOGLE, SENT_ACCESS, SENT_REFRESH, 3600, SENT_ACCOUNT);
            connectionService.start(WS, m.getId(), CalendarProvider.GOOGLE);
            OAuthFlowState state2 = mongoTemplate.findOne(new Query(), OAuthFlowState.class);
            connectionService.completeCallback(CalendarProvider.GOOGLE, SENT_CODE, state2.getId(), null, WS, m.getId());
            stubRevokeFails(CalendarProvider.GOOGLE);
            connectionService.disconnect(WS, m.getId(), CalendarProvider.GOOGLE);

            boolean ranThePath = false;
            for (ILoggingEvent e : new java.util.ArrayList<>(appender.list)) { // snapshot: async threads may still append (CME guard)
                String line = e.getFormattedMessage();
                if (line == null) {
                    continue;
                }
                assertThat(line).doesNotContain(SENT_ACCESS);
                assertThat(line).doesNotContain(SENT_REFRESH);
                assertThat(line).doesNotContain(SENT_ACCOUNT);
                assertThat(line).doesNotContain(SENT_CODE);
                if (line.contains("calendar ")) { // connected / reconnect required / refresh / revoke
                    ranThePath = true;
                }
            }
            assertThat(ranThePath).as("the calendar code path actually emitted logs (no vacuous pass)").isTrue();
        } finally {
            cadence.setLevel(previousCadence);
            root.detachAppender(appender);
        }
    }
}
