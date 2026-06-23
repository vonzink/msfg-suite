package com.msfg.los.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.los.platform.tenancy.TenantContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;

// Real Cognito JWT resource-server security — active in every profile EXCEPT "local".
// Local dev uses LocalDevSecurityConfig (no external IdP needed). dev/prod/test use this.
@Configuration
@Profile("!local")
@EnableMethodSecurity
public class SecurityConfig {

    // Role names (sans the ROLE_ prefix that hasAnyRole adds). PLATFORM_ADMIN is intentionally
    // absent — platform operators administer orgs, not loan files (loan-data access is staff-only).
    private static final String[] STAFF =
            { "LO", "PROCESSOR", "UNDERWRITER", "CLOSER", "MANAGER", "ADMIN" };
    // Staff PLUS the two self-service party roles, allowed only on the T6 read allowlist.
    private static final String[] STAFF_AND_PARTY =
            { "LO", "PROCESSOR", "UNDERWRITER", "CLOSER", "MANAGER", "ADMIN",
              "BORROWER", "REAL_ESTATE_AGENT" };
    // Staff PLUS the BORROWER role only (NO agent) — the T11 per-borrower own-data read allowlist.
    // Agent is excluded at the filter (defense-in-depth); the guard denies agent regardless.
    private static final String[] STAFF_AND_BORROWER =
            { "LO", "PROCESSOR", "UNDERWRITER", "CLOSER", "MANAGER", "ADMIN", "BORROWER" };

    private final TenantContextFilter tenantFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(TenantContextFilter tenantFilter, ObjectMapper objectMapper) {
        this.tenantFilter = tenantFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        ApiErrorAuthenticationEntryPoint entryPoint = new ApiErrorAuthenticationEntryPoint(objectMapper);
        ApiErrorAccessDeniedHandler accessDeniedHandler = new ApiErrorAccessDeniedHandler(objectMapper);
        http
            .cors(org.springframework.security.config.Customizer.withDefaults())
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(entryPoint)        // 401 → ApiError envelope
                .accessDeniedHandler(accessDeniedHandler))   // filter-layer 403 → ApiError envelope
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                // Per-tenant catalogs: a tenant ADMIN manages them (PLATFORM_ADMIN also allowed).
                // MUST precede the broad /api/admin/** rule — more specific matcher first.
                .requestMatchers("/api/admin/document-types/**", "/api/admin/folder-templates/**")
                    .hasAnyRole("ADMIN", "PLATFORM_ADMIN")
                // LO/Admin user administration (create user + reset password). MUST precede the broad
                // /api/admin/** (PLATFORM_ADMIN) rule — more specific matcher first. Role-by-role
                // assignment limits (an LO cannot mint staff/admin users) are enforced in the service.
                .requestMatchers("/api/admin/users", "/api/admin/users/**").hasAnyRole("LO", "ADMIN")
                .requestMatchers("/api/admin/**").hasRole("PLATFORM_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/loans").hasAnyRole("LO", "MANAGER", "ADMIN")
                // Borrower funnel hand-off (Phase A4): a signed-in BORROWER (or staff) creates a loan +
                // primary borrower row in suite. A literal path (NOT a loan-id), so it never matches the
                // UUID-constrained party allowlist below — it MUST precede the staff-only /api/** catch-all
                // or a borrower token would 403 there. The borrower→sub link is enforced in IntakeService.
                .requestMatchers(HttpMethod.POST, "/api/loans/intake").hasAnyRole(STAFF_AND_BORROWER)
                // Clone a loan ("Copy to new"): LO / PROCESSOR / MANAGER / ADMIN (Phase 2 T7). The
                // owning-LO access check is enforced in the service via the access guard. MUST precede
                // the broad /api/** rule — more specific matcher first.
                .requestMatchers(HttpMethod.POST, "/api/loans/*/clone").hasAnyRole("LO", "PROCESSOR", "MANAGER", "ADMIN")
                // Soft-delete a loan: LO-owner / MANAGER / ADMIN (Processor excluded, mortgage-app
                // parity). The owning-LO check is enforced in the service via the access guard.
                .requestMatchers(HttpMethod.DELETE, "/api/loans/*").hasAnyRole("LO", "MANAGER", "ADMIN")
                .requestMatchers("/api/org/**").hasRole("ADMIN")
                // ── Phase F T6 — party-role (BORROWER / REAL_ESTATE_AGENT) deny-by-default ───────
                // Borrowers and agents may match ONLY the four allowlisted reads below; their own-loan
                // scoping is then enforced at the controller by LoanAccessGuard.assertReadable. Every
                // other /api/** path (writes + non-allowlisted reads: income, financials, declarations,
                // reveal-ssn, conditions, notes, documents, search/number/pipeline, …) falls through to
                // the staff-only catch-all and a party token is 403 there at the filter.
                //
                // Ordering is load-bearing (first match wins):
                //   1. the staff-only loan LIST/lookup endpoints (pipeline, search, number) come FIRST,
                //      because the single-segment wildcard /api/loans/* below would otherwise also match
                //      /api/loans/search — keeping parties OUT of any list/lookup surface;
                //   2. then the party-inclusive allowlist (me, me/loans, GET /api/loans/{id},
                //      GET /api/loans/{id}/status/transitions);
                //   3. then the staff-only catch-all for everything else under /api/**.
                .requestMatchers(HttpMethod.GET, "/api/loans").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.GET, "/api/loans/search").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.GET, "/api/loans/number/**").hasAnyRole(STAFF)
                .requestMatchers(HttpMethod.GET, "/api/me", "/api/me/loans")
                    .hasAnyRole(STAFF_AND_PARTY)
                // UUID-constrained (NOT an Ant single-segment wildcard): the party allowlist must match
                // ONLY a real loan id, never a future single-segment GET like /api/loans/export or
                // /api/loans/stats — those would otherwise silently inherit BORROWER/REAL_ESTATE_AGENT
                // access. A non-UUID single-segment GET no longer matches here and falls through to the
                // staff-only /api/** catch-all below (party token → 403 at the filter). RegexRequestMatcher
                // is fully anchored (Matcher.matches) AND appends the query string to the matched URL, so
                // each pattern ends with an optional (\?.*)? — otherwise a party GET /api/loans/{id}?x=y
                // would not match and would fall through to the staff-only catch-all (a 403 regression).
                .requestMatchers(RegexRequestMatcher.regexMatcher(
                        HttpMethod.GET, "/api/loans/[0-9a-fA-F-]{36}(\\?.*)?")).hasAnyRole(STAFF_AND_PARTY)
                .requestMatchers(RegexRequestMatcher.regexMatcher(
                        HttpMethod.GET, "/api/loans/[0-9a-fA-F-]{36}/status/transitions(\\?.*)?")).hasAnyRole(STAFF_AND_PARTY)
                // ── Phase F T11 — per-borrower own-data GET reads (BORROWER, not agent) ──────────
                // The borrower's OWN per-borrower 1003 sections. UUID-constrained on BOTH the loan id
                // and the borrower id; the section is one of the six in-scope read controllers. The
                // borrower-IS-this-row check is enforced at the service via
                // LoanAccessGuard.assertBorrowerSelfReadable. GET only — writes (POST/PATCH/DELETE) on
                // these same paths are NOT matched here and fall through to the staff-only catch-all
                // (party token → 403). Loan-level aggregates (/income/summary, /assets/summary,
                // /liabilities/summary, /income/verifications, /assets/verifications) are deliberately
                // EXCLUDED: the alternation lists only the six base sections, anchored by (\?.*)? — a
                // trailing /summary or /verifications never matches → staff-only catch-all.
                .requestMatchers(RegexRequestMatcher.regexMatcher(HttpMethod.GET,
                        "/api/loans/[0-9a-fA-F-]{36}/borrowers/[0-9a-fA-F-]{36}/"
                        + "(income|employments|assets|liabilities|declarations|demographics)(\\?.*)?"))
                    .hasAnyRole(STAFF_AND_BORROWER)
                // Catch-all: every other API path is staff-only. Parties never reach writes or any
                // non-allowlisted read (they are not in this authority set → 403 at the filter).
                .requestMatchers("/api/**").hasAnyRole(STAFF)
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o
                .authenticationEntryPoint(entryPoint)
                .jwt(j -> j.jwtAuthenticationConverter(new OrgScopedJwtAuthenticationConverter())))
            .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
