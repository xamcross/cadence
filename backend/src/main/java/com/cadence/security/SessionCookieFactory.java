package com.cadence.security;

import com.cadence.config.AuthProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds and clears the {@code cad_session} cookie: HttpOnly + Secure + SameSite=Lax (research
 * D10/D1/FR-037). Same-origin (Cloudflare proxy) makes Lax sufficient and first-party.
 */
@Component
public class SessionCookieFactory {

    private final AuthProperties props;

    public SessionCookieFactory(AuthProperties props) {
        this.props = props;
    }

    public ResponseCookie build(String jwt, Duration maxAge) {
        return base(jwt).maxAge(maxAge).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(props.getSession().getCookieName(), value)
            .httpOnly(true)
            .secure(props.getSession().isSecureCookie())
            .sameSite("Lax")
            .path("/");
    }

    public String cookieName() {
        return props.getSession().getCookieName();
    }
}
