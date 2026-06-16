package com.cadence.security;

import com.cadence.config.AuthProperties;
import com.cadence.service.SessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Four ordered filter chains (research D7 + F22 D4):
 *   1. /actuator/**            -> permitAll (preserves the F00 management-port contract; unchanged)
 *   2. /api/public/**,         -> permitAll, CSRF-exempt (login/invite/reset have no session yet)
 *      /api/candidate/**
 *   3. /api/webhooks/email/**  -> permitAll, CSRF-exempt, STATELESS (F22 provider webhook; the real auth is
 *                                 the in-controller HMAC signature, not a session — research D4)
 *   4. everything else (incl.  -> deny-by-default authenticated(); OIDC login; session-cookie filter;
 *      /oauth2/**, callback)      CSRF via readable cookie; 401 entry point for APIs (no redirect).
 *
 * Defining SecurityFilterChain beans makes Boot's ManagementWebSecurityAutoConfiguration back off,
 * so the @Order(1) actuator chain must stay (CLAUDE.md).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurityChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/actuator/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain publicApiSecurityChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/api/public/**", "/api/candidate/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .build();
    }

    /**
     * F22 (research D4): the inbound provider bounce/delivery webhook is a machine caller with no session.
     * The @Order(2) public matcher (/api/public,/api/candidate) does NOT cover /api/webhooks, so without this
     * dedicated chain the @Order(4) /api/** entry point would 401 the unauthenticated provider POST before the
     * in-controller HMAC signature check runs. This chain routes ONLY /api/webhooks/email/** -> permitAll,
     * CSRF-exempt + STATELESS (the signature is the real gate). It does NOT widen the @Order(2) public chain or
     * the @Order(4) /api/** 401 / 403 / actuator-404 contracts (asserted by WebhookSecurityChainTest). Placed
     * ordered before the catch-all chain but it only matches the webhook path.
     */
    @Bean
    @Order(3)
    SecurityFilterChain webhookSecurityChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/api/webhooks/email/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain applicationSecurityChain(
            HttpSecurity http,
            SessionService sessionService,
            SessionCookieFactory cookieFactory,
            OidcLoginSuccessHandler successHandler,
            OidcLoginFailureHandler failureHandler,
            RestAccessDeniedHandler accessDeniedHandler,
            AuthProperties authProps) throws Exception {

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();

        // F01.1: a session that expires DURING calendar consent must not strand the member on a bare
        // 401 — redirect the top-level callback GET to the SPA error page instead (Security #1). This is
        // registered BEFORE the /api/** 401 mapping (entry points fire in registration order and the
        // callback path matches /api/**), so a non-callback /api/** path still 401s.
        AuthenticationEntryPoint calendarCallbackEntryPoint = (request, response, ex) ->
            response.sendRedirect(authProps.getSpaBaseUrl() + "/calendar/connections?error=session_expired");

        return http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            // API clients get 401 (FR-010), never a redirect to the IdP (the SPA initiates SSO
            // explicitly via /oauth2/authorization/...). Non-API protected resources keep the
            // default 403 posture (preserves the F00 actuator-on-public-port contract).
            .exceptionHandling(e -> e
                .defaultAuthenticationEntryPointFor(
                    calendarCallbackEntryPoint,
                    new AntPathRequestMatcher("/api/internal/calendar/connections/*/callback"))
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), new AntPathRequestMatcher("/api/**"))
                .defaultAuthenticationEntryPointFor(
                    new Http403ForbiddenEntryPoint(), new AntPathRequestMatcher("/**"))
                // F02 (D5): authenticated-but-unauthorized -> JSON {error,message} 403 on this main
                // chain only. The @Order(1) actuator + @Order(2) public chains are permitAll and never
                // reach here, so the F00 actuator-404 and F01 /api/** 401 contracts are preserved.
                .accessDeniedHandler(accessDeniedHandler))
            .oauth2Login(o -> o
                .loginPage("/oauth2/authorization/cadence-oidc")
                .successHandler(successHandler)
                .failureHandler(failureHandler))
            // Per-request session-cookie authentication (our own session, not the IdP token).
            .addFilterBefore(new SessionCookieAuthFilter(sessionService, cookieFactory),
                UsernamePasswordAuthenticationFilter.class)
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
