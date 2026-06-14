package com.cadence.security;

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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Three ordered filter chains (research D7):
 *   1. /actuator/**            -> permitAll (preserves the F00 management-port contract; unchanged)
 *   2. /api/public/**,         -> permitAll, CSRF-exempt (login/invite/reset have no session yet)
 *      /api/candidate/**
 *   3. everything else (incl.  -> deny-by-default authenticated(); OIDC login; session-cookie filter;
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

    @Bean
    @Order(3)
    SecurityFilterChain applicationSecurityChain(
            HttpSecurity http,
            SessionService sessionService,
            SessionCookieFactory cookieFactory,
            OidcLoginSuccessHandler successHandler,
            OidcLoginFailureHandler failureHandler,
            RestAccessDeniedHandler accessDeniedHandler) throws Exception {

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();

        return http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            // API clients get 401 (FR-010), never a redirect to the IdP (the SPA initiates SSO
            // explicitly via /oauth2/authorization/...). Non-API protected resources keep the
            // default 403 posture (preserves the F00 actuator-on-public-port contract).
            .exceptionHandling(e -> e
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
