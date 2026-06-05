package com.msfg.los.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS policy for the REST API ({@code /api/**}).
 *
 * <p>Security constraints (enforced here, never relax without review):
 * <ul>
 *   <li>Exact-origin allowlist only — no wildcards, no {@code setAllowedOriginPatterns("*")}.</li>
 *   <li>{@code allowCredentials = false} — auth is Bearer JWT in Authorization header, not cookies.
 *       Credentials-false + exact origins is safe; combining {@code *} with credentials is forbidden
 *       by the spec and rejected by browsers anyway.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        // Exact origin allowlist — populated from los.cors.allowed-origins; empty = deny all.
        c.setAllowedOrigins(props.getAllowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        c.setAllowCredentials(false);
        c.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }
}
