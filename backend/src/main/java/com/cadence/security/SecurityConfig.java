package com.cadence.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

// Management port 8081 is isolated at the network layer (Fly.io internal network).
// Spring Security only applies to the main application port 8080 — actuator traffic
// never reaches this filter chain because it is served by a separate WebServer instance.
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain applicationSecurityChain(HttpSecurity http) throws Exception {
        // TODO: configure JWT/OAuth2 resource server when auth feature is implemented (F-auth)
        return http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .csrf(csrf -> csrf.disable())
            .build();
    }
}
