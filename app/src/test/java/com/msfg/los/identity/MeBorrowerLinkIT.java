package com.msfg.los.identity;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end ITs for the {@code /me} verified-email borrower auto-link gates (Phase F T5a).
 *
 * <p>Exercises the {@code UserAccountService} gating that sits above the {@code BorrowerUserLinker}
 * port (whose link mechanics are proven in {@code BorrowerUserLinkerIT}):
 * <ul>
 *   <li>(a) email_verified=false → NO stamp even with a single email match</li>
 *   <li>(c) BORROWER + verified + single match → stamped; {@code /me} still 200 with role BORROWER</li>
 *   <li>(h) REAL_ESTATE_AGENT (agent) is NOT auto-linked even when an email match exists</li>
 *   <li>idempotent: a second {@code /me} keeps the single link</li>
 * </ul>
 *
 * The borrower row is seeded in {@code DEFAULT_ORG} (the JWT's org) so the {@code @TenantId} filter
 * during {@code /me} resolves it. The Testcontainers JdbcTemplate connects as superuser (RLS bypass)
 * so the assertion reads {@code user_id} directly.
 */
class MeBorrowerLinkIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private UUID seedLoan() {
        UUID loan = UUID.randomUUID();
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loan, "MEBUL-" + loan.toString().substring(0, 8), UUID.randomUUID(), DEFAULT_ORG);
        return loan;
    }

    private UUID seedBorrower(UUID loan, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "insert into borrower_party " +
            "(id,version,org_id,loan_id,is_primary,ordinal,email,user_id) " +
            "values (?,0,?::uuid,?,true,0,?,null)",
            id, DEFAULT_ORG, loan, email);
        return id;
    }

    private UUID userIdOf(UUID borrowerId) {
        return jdbc.queryForObject(
            "select user_id from borrower_party where id = ?", UUID.class, borrowerId);
    }

    private RequestPostProcessor borrower(String sub, String email, boolean emailVerified) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG)
                        .claim("name", "Bo Rower").claim("email", email)
                        .claim("email_verified", emailVerified))
                .authorities(new SimpleGrantedAuthority("ROLE_BORROWER"));
    }

    // ── (c) verified + single match → stamped; /me 200 with BORROWER role ────────

    @Test
    void verifiedBorrowerWithSingleMatch_isLinked_andMeReturnsRole() throws Exception {
        String email = "link-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        UUID b = seedBorrower(seedLoan(), email);
        String sub = UUID.randomUUID().toString();

        mvc.perform(get("/api/me").with(borrower(sub, email, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("BORROWER"))
                .andExpect(jsonPath("$.data.roles[0]").value("ROLE_BORROWER"));

        assertThat(userIdOf(b)).as("verified single-match borrower is stamped with the sub")
                .isEqualTo(UUID.fromString(sub));
    }

    @Test
    void link_isIdempotentAcrossRepeatedMeCalls() throws Exception {
        String email = "idem-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        UUID b = seedBorrower(seedLoan(), email);
        String sub = UUID.randomUUID().toString();

        mvc.perform(get("/api/me").with(borrower(sub, email, true))).andExpect(status().isOk());
        mvc.perform(get("/api/me").with(borrower(sub, email, true))).andExpect(status().isOk());

        assertThat(userIdOf(b)).as("still the single original link").isEqualTo(UUID.fromString(sub));
    }

    // ── (a) email_verified=false → NO stamp even with a single match ─────────────

    @Test
    void unverifiedEmail_isNotLinked_evenWithSingleMatch() throws Exception {
        String email = "unverified-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        UUID b = seedBorrower(seedLoan(), email);
        String sub = UUID.randomUUID().toString();

        mvc.perform(get("/api/me").with(borrower(sub, email, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("BORROWER"));

        assertThat(userIdOf(b)).as("unverified email must NEVER auto-link").isNull();
    }

    // ── (h) agent role → not auto-linked ────────────────────────────────────────

    @Test
    void agentRole_isNotAutoLinked_evenWhenEmailMatches() throws Exception {
        String email = "agent-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        UUID b = seedBorrower(seedLoan(), email);
        String sub = UUID.randomUUID().toString();

        RequestPostProcessor agent = jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG)
                        .claim("name", "Ann Agent").claim("email", email)
                        .claim("email_verified", true))
                .authorities(new SimpleGrantedAuthority("ROLE_REAL_ESTATE_AGENT"));

        mvc.perform(get("/api/me").with(agent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("REAL_ESTATE_AGENT"));

        assertThat(userIdOf(b)).as("agents are never auto-linked to a borrower row").isNull();
    }
}
