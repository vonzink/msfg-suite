package com.msfg.los.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * LOCAL DEV ONLY (@Profile("local")). Lets the app boot and be manually exercised (Swagger/curl)
 * without a real Cognito user pool: every request is authenticated as a fixed dev ADMIN principal.
 * Never active in dev/prod/test — those use {@link SecurityConfig} (real Cognito JWT validation).
 */
@Configuration
@Profile("local")
public class LocalDevSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalDevSecurityConfig.class);
    /** Stable dev principal id — use this as loanOfficerId when creating loans locally. */
    public static final String DEV_USER_ID = "00000000-0000-0000-0000-000000000001";

    @Bean
    SecurityFilterChain localFilterChain(HttpSecurity http) throws Exception {
        log.warn("=== LOCAL DEV SECURITY ACTIVE: all requests run as dev ADMIN ({}). NEVER use the 'local' profile in production. ===", DEV_USER_ID);
        http
            .csrf(c -> c.disable())
            .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
            // AFTER SecurityContextHolderFilter: that filter loads (and later clears) the context,
            // so we must set our dev principal into the already-established context, not before it
            // (running before it gets clobbered when it loads a fresh empty context).
            .addFilterAfter(new DevPrincipalFilter(), SecurityContextHolderFilter.class);
        return http.build();
    }

    /** Injects a fixed dev ADMIN JwtAuthenticationToken so CurrentUser + access guards work locally. */
    static class DevPrincipalFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            Jwt jwt = Jwt.withTokenValue("local-dev")
                .header("alg", "none")
                .subject(DEV_USER_ID)
                .claim("cognito:groups", List.of("ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
            var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, res);
        }
    }
}
