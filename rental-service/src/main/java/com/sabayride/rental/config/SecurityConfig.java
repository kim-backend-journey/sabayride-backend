package com.sabayride.rental.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * DEVELOPMENT ONLY — every endpoint is open and CORS is wide.
 *
 * Replaced by JWT validation against identity-service's JWKS endpoint before
 * anything is deployed. Shipping this as-is would expose every endpoint,
 * including the admin ones, to the whole internet.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Safe to disable: this API is stateless and token-based, so there
            // is no session cookie for a CSRF attack to ride on.
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * The dashboard runs in a browser on a different port, which makes every
     * call cross-origin. Without this the browser blocks the RESPONSE — the
     * request reaches the server and succeeds, so the server log looks fine
     * while the dashboard shows nothing. That asymmetry is why CORS errors are
     * so confusing the first time.
     *
     * Flutter is unaffected; CORS is a browser rule, not an HTTP one.
     *
     * In production this becomes an explicit list of real origins. A deployed
     * API that allows "*" lets any website on the internet make authenticated
     * calls on behalf of a logged-in user.
     */
    @Bean
    CorsConfigurationSource corsSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOriginPatterns(List.of(
                "http://localhost:*",       // Vite, React, Angular dev servers
                "http://127.0.0.1:*"
        ));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("Location"));
        c.setAllowCredentials(true);
        c.setMaxAge(3600L);   // cache the preflight for an hour

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", c);
        return source;
    }
}
