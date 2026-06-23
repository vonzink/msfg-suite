package com.msfg.los.config;

import com.msfg.los.platform.tenancy.TenantContextFilter;
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
    /** Default org (MSFG seed) — set on dev JWT so TenantContextFilter stamps reads/writes. */
    public static final String DEV_ORG_ID = "00000000-0000-0000-0000-0000000000aa";

    private final TenantContextFilter tenantContextFilter;

    public LocalDevSecurityConfig(TenantContextFilter tenantContextFilter) {
        this.tenantContextFilter = tenantContextFilter;
    }

    @Bean
    SecurityFilterChain localFilterChain(HttpSecurity http) throws Exception {
        log.warn("=== LOCAL DEV SECURITY ACTIVE: all requests run as dev ADMIN ({}). NEVER use the 'local' profile in production. ===", DEV_USER_ID);
        http
            .cors(org.springframework.security.config.Customizer.withDefaults())
            .csrf(c -> c.disable())
            .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
            // AFTER SecurityContextHolderFilter: that filter loads (and later clears) the context,
            // so we must set our dev principal into the already-established context, not before it
            // (running before it gets clobbered when it loads a fresh empty context).
            .addFilterAfter(new DevPrincipalFilter(), SecurityContextHolderFilter.class)
            .addFilterAfter(tenantContextFilter, DevPrincipalFilter.class);
        return http.build();
    }

    /**
     * Injects a dev principal so CurrentUser + access guards work locally WITHOUT Cognito.
     * Honors optional dev headers so we can act as a borrower/agent/LO for cross-system testing:
     *   X-Dev-Sub   : UUID subject        (default DEV_USER_ID)
     *   X-Dev-Roles : CSV Cognito groups  (default "Admin")  e.g. "Borrower"
     *   X-Dev-Org   : UUID org id         (default DEV_ORG_ID)
     * Absent headers -> the original fixed dev ADMIN behavior (backward compatible).
     *
     * SECURITY: trust-the-header is a LOCAL-ONLY test seam. This class is @Profile("local") and is
     * NEVER wired in dev/prod/test (those use SecurityConfig + real Cognito JWT). Do not lift it out.
     */
    static class DevPrincipalFilter extends OncePerRequestFilter {
        private static java.util.List<String> rolesFor(java.util.List<String> groups) {
            java.util.List<String> auth = new java.util.ArrayList<>();
            for (String g : groups) {
                switch (g.trim()) {
                    case "Admin" -> { auth.add("ROLE_ADMIN"); auth.add("ROLE_PLATFORM_ADMIN"); }
                    case "Manager" -> auth.add("ROLE_MANAGER");
                    case "LO" -> auth.add("ROLE_LO");
                    case "Processor" -> auth.add("ROLE_PROCESSOR");
                    case "Borrower" -> auth.add("ROLE_BORROWER");
                    case "RealEstateAgent" -> auth.add("ROLE_REAL_ESTATE_AGENT");
                    default -> { /* unknown dev role ignored */ }
                }
            }
            return auth;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String sub = headerOr(req, "X-Dev-Sub", DEV_USER_ID);
            String org = headerOr(req, "X-Dev-Org", DEV_ORG_ID);
            String rolesCsv = headerOr(req, "X-Dev-Roles", "Admin");
            java.util.List<String> groups = java.util.Arrays.stream(rolesCsv.split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).toList();

            Jwt jwt = Jwt.withTokenValue("local-dev")
                .header("alg", "none")
                .subject(sub)
                .claim("cognito:groups", groups)
                .claim("org_id", org)
                .claim("email_verified", true)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
            java.util.List<SimpleGrantedAuthority> authorities = rolesFor(groups).stream()
                    .map(SimpleGrantedAuthority::new).toList();
            var auth = new JwtAuthenticationToken(jwt, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, res);
        }

        private static String headerOr(HttpServletRequest req, String name, String dflt) {
            String v = req.getHeader(name);
            return (v == null || v.isBlank()) ? dflt : v.trim();
        }
    }
}
