package com.sabayride.rental.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * DEVELOPMENT ONLY — everything is open.
 *
 * This is replaced by JWT validation against identity-service's JWKS endpoint
 * before anything is deployed. It exists now so the booking endpoints can be
 * exercised while the auth service does not yet exist.
 *
 * Leaving this in a deployed build would expose every endpoint, including the
 * admin ones, to the whole internet.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Safe to disable: this API is stateless and token-based, so there
            // is no session cookie for a CSRF attack to ride on.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
