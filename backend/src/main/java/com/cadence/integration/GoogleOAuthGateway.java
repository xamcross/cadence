package com.cadence.integration;

import com.cadence.config.CalendarOAuthProperties;
import com.cadence.domain.CalendarProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Google calendar OAuth gateway (research D7). Scope is free/busy-only ({@code calendar.freebusy});
 * {@code access_type=offline} + {@code prompt=consent} guarantee a refresh token is returned.
 */
@Component
public class GoogleOAuthGateway extends AbstractOAuthGateway {

    public GoogleOAuthGateway(CalendarOAuthProperties props) {
        super(props);
    }

    @Override
    public CalendarProvider id() {
        return CalendarProvider.GOOGLE;
    }

    @Override
    protected CalendarOAuthProperties.Provider config() {
        return props.getGoogle();
    }

    @Override
    protected Map<String, String> extraAuthParams() {
        return Map.of("access_type", "offline", "prompt", "consent");
    }
}
