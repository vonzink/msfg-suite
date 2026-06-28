package com.msfg.los.tenancy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that the borrower / agent portal reads work with Postgres RLS ENGAGED — i.e. when
 * the app's runtime datasource connects as the non-owner {@code app_user} role, exactly as in
 * production. This is the faithful reproduction of the bug the connection-level GUC fix
 * ({@link GucConnectionDataSource}) closes: the controller-layer access-guard linkage checks
 * ({@code isBorrowerOnLoan} / {@code loanIdsForBorrower}) run OUTSIDE a {@code @Transactional}
 * boundary, so before the fix they executed with no {@code app.current_org} GUC and fail-closed RLS
 * hid the borrower_party rows → 403 / empty {@code /me/loans}, even for a correctly-linked borrower.
 *
 * <p>Unlike the other ITs (which connect as the Testcontainers superuser and bypass RLS), this one:
 * <ul>
 *   <li>runs the app datasource as {@code app_user} (provisioned a LOGIN here), so RLS is enforced;</li>
 *   <li>runs Flyway as the owner/superuser, so migrations + the V3 DEFAULT_ORG seed still apply;</li>
 *   <li>seeds fixtures over a separate superuser connection (RLS bypassed) to avoid the chicken-and-egg
 *       of writing tenant rows before a request context exists.</li>
 * </ul>
 * It therefore also validates the intended production deployment posture (owner = Flyway, non-owner =
 * runtime) actually boots and serves the borrower-portal reads.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(BorrowerPortalRlsIT.TestBeans.class)
class BorrowerPortalRlsIT {

    static final String DEFAULT_ORG = "00000000-0000-0000-0000-0000000000aa";
    static final String OTHER_ORG = "00000000-0000-0000-0000-0000000000ef";
    static final String APP_USER_PW = "app_user_pw";

    static final PostgreSQLContainer<?> PG;

    static {
        PG = new PostgreSQLContainer<>("postgres:16");
        PG.start();
        // Provision app_user with a LOGIN BEFORE Flyway runs: V4's `create role ... if not exists`
        // then no-ops and preserves this LOGIN, while still applying the V4/V18/V24 grants.
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var st = c.createStatement()) {
            st.execute("""
                do $$ begin
                  if not exists (select 1 from pg_roles where rolname = 'app_user') then
                    create role app_user login password '%s' nosuperuser nocreatedb nocreaterole noinherit;
                  else
                    alter role app_user login password '%s';
                  end if;
                end $$;""".formatted(APP_USER_PW, APP_USER_PW));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry reg) {
        // Runtime datasource = NON-OWNER app_user -> Postgres RLS is enforced at runtime (as in prod).
        reg.add("spring.datasource.url", PG::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "app_user");
        reg.add("spring.datasource.password", () -> APP_USER_PW);
        // Flyway = OWNER (the container superuser) so DDL + the DEFAULT_ORG seed run as the owner.
        reg.add("spring.flyway.url", PG::getJdbcUrl);
        reg.add("spring.flyway.user", PG::getUsername);
        reg.add("spring.flyway.password", PG::getPassword);
    }

    @Autowired MockMvc mvc;

    @Test
    void linkedBorrower_readsOwnLoanSummaryAndMeLoans_underRls() throws Exception {
        UUID loan = UUID.randomUUID();
        String sub = UUID.randomUUID().toString();
        seedLoan(loan, DEFAULT_ORG, "RLS-OK-1");
        seedLinkedBorrower(loan, DEFAULT_ORG, sub, "borrower-ok@example.com");

        RequestPostProcessor borrower = borrower(sub, DEFAULT_ORG);

        // The non-transactional access-guard linkage check now sees the borrower_party row under RLS.
        mvc.perform(get("/api/loans/{id}", loan).with(borrower))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(loan.toString()));

        // /me/loans BORROWER branch (loanIdsForBorrower) also runs outside a transaction -> covered.
        mvc.perform(get("/api/me/loans?size=50").with(borrower))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(loan.toString()));
    }

    @Test
    void unlinkedBorrower_isDeniedUnderRls() throws Exception {
        UUID loan = UUID.randomUUID();
        seedLoan(loan, DEFAULT_ORG, "RLS-UNLINKED");
        // A real borrower row exists, but for a DIFFERENT user — the probe sub is not linked.
        seedLinkedBorrower(loan, DEFAULT_ORG, UUID.randomUUID().toString(), "someone-else@example.com");

        mvc.perform(get("/api/loans/{id}", loan).with(borrower(UUID.randomUUID().toString(), DEFAULT_ORG)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void crossTenantBorrower_cannotSeeOtherOrgsLoan_underRls() throws Exception {
        UUID loan = UUID.randomUUID();
        String sub = UUID.randomUUID().toString();
        seedLoan(loan, DEFAULT_ORG, "RLS-XTENANT");
        seedLinkedBorrower(loan, DEFAULT_ORG, sub, "xtenant@example.com");
        seedOrg(OTHER_ORG);

        // Same sub, but a token scoped to a DIFFERENT org -> tenant filter + RLS hide the loan -> 404.
        mvc.perform(get("/api/loans/{id}", loan).with(borrower(sub, OTHER_ORG)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/me/loans?size=50").with(borrower(sub, OTHER_ORG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // --- helpers -------------------------------------------------------------------------------

    private static RequestPostProcessor borrower(String sub, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org)
                        .claim("email", "b@example.com").claim("email_verified", true))
                .authorities(new SimpleGrantedAuthority("ROLE_BORROWER"));
    }

    private void seedOrg(String org) {
        execAsOwner(
            "insert into organization (id,version,name,slug,status,settings) " +
            "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
            org, "org-" + org, "org-" + org);
    }

    private void seedLoan(UUID loan, String org, String number) {
        execAsOwner(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?::uuid,0,?,?::uuid,'STARTED',?::uuid)",
            loan.toString(), number, UUID.randomUUID().toString(), org);
    }

    private void seedLinkedBorrower(UUID loan, String org, String sub, String email) {
        execAsOwner(
            "insert into borrower_party (id,version,org_id,loan_id,is_primary,ordinal,email,user_id) " +
            "values (?::uuid,0,?::uuid,?::uuid,true,0,?,?::uuid)",
            UUID.randomUUID().toString(), org, loan.toString(), email, sub);
    }

    /** Seed over a direct OWNER (superuser) connection — RLS bypassed — so fixtures land regardless of GUC. */
    private void execAsOwner(String sql, Object... args) {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, String.valueOf(args[i]));
            }
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("seed failed: " + sql, e);
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> { throw new UnsupportedOperationException("JwtDecoder not used in tests"); };
        }
    }
}
