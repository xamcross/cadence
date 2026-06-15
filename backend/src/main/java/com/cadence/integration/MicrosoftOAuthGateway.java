package com.cadence.integration;

import com.cadence.config.CalendarOAuthProperties;
import com.cadence.domain.CalendarProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Microsoft 365 calendar OAuth gateway (research D7). Scope is {@code Calendars.Read offline_access}
 * (Graph free/busy via getSchedule has no narrower delegated scope; field projection at query time is
 * F11's documented mitigation, ISSUE-2). {@code offline_access} (in the scope) yields a refresh token;
 * {@code prompt=consent} forces re-consent so the refresh token is reliably issued.
 */
@Component
public class MicrosoftOAuthGateway extends AbstractOAuthGateway {

    public MicrosoftOAuthGateway(CalendarOAuthProperties props) {
        super(props);
    }

    @Override
    public CalendarProvider id() {
        return CalendarProvider.MICROSOFT;
    }

    @Override
    protected CalendarOAuthProperties.Provider config() {
        return props.getMicrosoft();
    }

    @Override
    protected Map<String, String> extraAuthParams() {
        return Map.of("prompt", "consent");
    }
}
