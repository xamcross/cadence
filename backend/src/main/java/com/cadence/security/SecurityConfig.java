package com.cadence.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

// Two ordered filter chains. Defining ANY SecurityFilterChain bean makes Spring Boot's
// ManagementWebSecurityAutoConfiguration back off — so without an explicit actuator chain,
// the main `authenticated()` rule below also applies to the management context and returns
// 403 for /actuator/health (confirmed by ActuatorPortTest). The @Order(1) chain restores
// open access to the actuator endpoints. The management port (8081) is additionally
// network-isolated in production (Fly.io internal network); permitAll here is safe.
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurityChain(HttpSecurity http) throws Exception {
        // Match by path ("/actuator/**") rather than EndpointRequest.toAnyEndpoint(): on the
        // management port this permits the actuator endpoints (200), and on the public port it
        // permits the (unmapped) /actuator path so it falls through to a 404 — the behaviour the
        // management-endpoints contract requires. EndpointRequest matches nothing on the public
        // port, which would let the main authenticated() chain return 403 instead of 404.
        return http
            .securityMatcher("/actuator/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurityChain(HttpSecurity http) throws Exception {
        // TODO: configure JWT/OAuth2 resource server when auth feature is implemented (F-auth)
        return http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .csrf(csrf -> csrf.disable())
            .build();
    }
}
