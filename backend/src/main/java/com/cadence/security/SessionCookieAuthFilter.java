package com.cadence.security;

import com.cadence.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Per-request authentication from the {@code cad_session} cookie (research D1/D7). Validates via
 * {@link SessionService}; on success sets the SecurityContext with the member id as principal and a
 * {@code ROLE_<role>} authority (consumed by F02), and refreshes the cookie when a throttled
 * sliding renewal occurred. Invalid/absent cookies leave the request anonymous so the
 * deny-by-default chain returns 401.
 */
public class SessionCookieAuthFilter extends OncePerRequestFilter {

    private final SessionService sessionService;
    private final SessionCookieFactory cookieFactory;

    public SessionCookieAuthFilter(SessionService sessionService, SessionCookieFactory cookieFactory) {
        this.sessionService = sessionService;
        this.cookieFactory = cookieFactory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        readCookie(request).ifPresent(token -> sessionService.validate(token).ifPresent(v -> {
            var auth = new UsernamePasswordAuthenticationToken(
                v.principal(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + v.principal().role().name())));
            SecurityContextHolder.getContext().setAuthentication(auth);
            if (v.renewed()) {
                response.addHeader(HttpHeaders.SET_COOKIE,
                    cookieFactory.build(token, v.cookieMaxAge()).toString());
            }
        }));
        filterChain.doFilter(request, response);
    }

    private Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String name = cookieFactory.cookieName();
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }
}
