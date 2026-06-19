package com.msfg.los.identity;

import com.msfg.los.loan.service.LoanAgentService;
import com.msfg.los.platform.tenancy.TenantContextHolder;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service-layer integration test for {@link LoanAgentService}.
 *
 * <p>Uses Testcontainers Postgres + Spring context (via {@link AbstractIntegrationTest}).
 * Seeds rows via JDBC (superuser, bypasses RLS for setup), then binds tenant context via
 * {@link TenantContextHolder#set} so Hibernate's {@code @TenantId} filter resolves correctly.
 *
 * <p>Key cases:
 * <ol>
 *   <li>isAgentOnLoan → true for the real (org, loan, user) triple</li>
 *   <li>isAgentOnLoan → false for an unlinked user in the same org</li>
 *   <li>Two-org isolation: a link in ORG_A is NOT visible when context is ORG_B</li>
 *   <li>loanIdsForAgent returns the correct loan set, org-scoped</li>
 * </ol>
 *
 * Distinct org UUIDs (3-hex suffix range, no overlap with other RlsITs):
 * {@code 000000000a03} / {@code 000000000a04}.
 */
class LoanAgentServiceIT extends AbstractIntegrationTest {

    // Unique org pairs — no overlap with LoanAgentRlsIT (a01/a02) or other suites
    static final UUID ORG_A = UUID.fromString("00000000-0000-0000-0000-000000000a03");
    static final UUID ORG_B = UUID.fromString("00000000-0000-0000-0000-000000000a04");

    @Autowired
    LoanAgentService loanAgentService;

    @Autowired
    JdbcTemplate jdbc;

    UUID loanA;   // loan in ORG_A
    UUID loanA2;  // second loan in ORG_A
    UUID loanB;   // loan in ORG_B

    UUID agentUser;    // user linked to loanA in ORG_A (and loanA2)
    UUID otherUser;    // user NOT linked to any loan in ORG_A

    @BeforeEach
    void seed() {
        // Orgs (idempotent)
        for (Object[] row : new Object[][]{{ORG_A, "la-svc-org-a"}, {ORG_B, "la-svc-org-b"}}) {
            jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                row[0].toString(), row[1], row[1]);
        }

        // Loans
        loanA  = UUID.randomUUID();
        loanA2 = UUID.randomUUID();
        loanB  = UUID.randomUUID();
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanA, "LASVC-A1-" + loanA.toString().substring(0, 8), UUID.randomUUID(), ORG_A);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanA2, "LASVC-A2-" + loanA2.toString().substring(0, 8), UUID.randomUUID(), ORG_A);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanB, "LASVC-B1-" + loanB.toString().substring(0, 8), UUID.randomUUID(), ORG_B);

        // Users (Cognito subs — arbitrary UUIDs)
        agentUser = UUID.randomUUID();
        otherUser = UUID.randomUUID();

        // Agent linked to loanA and loanA2 in ORG_A
        jdbc.update(
            "insert into loan_agent (id,version,org_id,loan_id,user_id,agent_role,ordinal) " +
            "values (?,0,?::uuid,?,?,'BUYERS_AGENT',0)",
            UUID.randomUUID(), ORG_A, loanA, agentUser);
        jdbc.update(
            "insert into loan_agent (id,version,org_id,loan_id,user_id,agent_role,ordinal) " +
            "values (?,0,?::uuid,?,?,'LISTING_AGENT',1)",
            UUID.randomUUID(), ORG_A, loanA2, agentUser);

        // Same agentUser linked in ORG_B to loanB (cross-org isolation probe)
        jdbc.update(
            "insert into loan_agent (id,version,org_id,loan_id,user_id,agent_role,ordinal) " +
            "values (?,0,?::uuid,?,?,'BUYERS_AGENT',0)",
            UUID.randomUUID(), ORG_B, loanB, agentUser);
    }

    // ── isAgentOnLoan ────────────────────────────────────────────────────────

    @Test
    void isAgentOnLoan_trueForLinkedUser() {
        TenantContextHolder.set(ORG_A);
        assertThat(loanAgentService.isAgentOnLoan(loanA, agentUser))
                .as("agentUser is linked to loanA in ORG_A")
                .isTrue();
    }

    @Test
    void isAgentOnLoan_falseForUnlinkedUser() {
        TenantContextHolder.set(ORG_A);
        assertThat(loanAgentService.isAgentOnLoan(loanA, otherUser))
                .as("otherUser has no link to loanA in ORG_A")
                .isFalse();
    }

    @Test
    void isAgentOnLoan_twoOrgIsolation_orgBContextDoesNotMatchOrgALink() {
        // agentUser IS linked to loanA in ORG_A, but the tenant context is ORG_B.
        // The @TenantId filter must scope the query to ORG_B → no match.
        TenantContextHolder.set(ORG_B);
        assertThat(loanAgentService.isAgentOnLoan(loanA, agentUser))
                .as("ORG_B context must not see ORG_A loan_agent rows (cross-org leak)")
                .isFalse();
    }

    // ── loanIdsForAgent ──────────────────────────────────────────────────────

    @Test
    void loanIdsForAgent_returnsLinkedLoansInOrg() {
        TenantContextHolder.set(ORG_A);
        List<UUID> ids = loanAgentService.loanIdsForAgent(agentUser);
        assertThat(ids)
                .as("agentUser is linked to loanA and loanA2 in ORG_A")
                .containsExactlyInAnyOrder(loanA, loanA2);
    }

    @Test
    void loanIdsForAgent_emptyForUnlinkedUser() {
        TenantContextHolder.set(ORG_A);
        assertThat(loanAgentService.loanIdsForAgent(otherUser))
                .as("otherUser has no loan_agent rows in ORG_A")
                .isEmpty();
    }

    @Test
    void loanIdsForAgent_twoOrgIsolation_orgBContextOnlySeesOrgBRows() {
        // agentUser has 2 rows in ORG_A and 1 row in ORG_B.
        // With ORG_B context only loanB should surface.
        TenantContextHolder.set(ORG_B);
        List<UUID> ids = loanAgentService.loanIdsForAgent(agentUser);
        assertThat(ids)
                .as("ORG_B context must see only ORG_B loan_agent rows")
                .containsExactly(loanB);
    }
}
