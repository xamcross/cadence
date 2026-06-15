package com.cadence.integration;

import com.cadence.config.CalendarOAuthProperties;
import com.cadence.domain.CalendarProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Microsoft 365 calendar OAuth gateway. Scope is config-driven (see {@code calendar.oauth.microsoft.scope}).
 * As of F11 (research D1) it is {@code openid profile email offline_access Calendars.ReadWrite}:
 * {@code Calendars.ReadWrite} is required to write events (Graph has no owned-events-only delegated scope,
 * so it is broader than F10's Google grant — §VIII justification in the F11 plan); {@code openid profile
 * email} make Graph issue an id_token whose email/UPN becomes {@code providerAccountId}, the mailbox
 * address Graph {@code getSchedule} requires (D2a). The getSchedule no-content guarantee is enforced by
 * the F11 adapter's parse-discipline (start/end/status only) + a non-circular test, NOT a field
 * projection. {@code offline_access} yields a refresh token; {@code prompt=consent} forces re-consent so
 * the refresh token is reliably issued (and a pre-F11 read-only connection re-consents under the new scope).
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
