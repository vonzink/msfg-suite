package com.msfg.los.identity;

import com.msfg.los.loan.service.LoanLinkageResolver;
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
 * Service-layer integration test for the borrower side of self-scoping:
 * {@link LoanLinkageResolver} (interface in loan-core) implemented by the parties adapter.
 *
 * <p>Seeds rows via JDBC superuser (bypasses RLS for setup), then binds tenant context via
 * {@link TenantContextHolder#set} so Hibernate's {@code @TenantId} filter resolves correctly.
 *
 * <p>Key cases:
 * <ol>
 *   <li>isBorrowerOnLoan → true for the real (org, loan, user) triple</li>
 *   <li>isBorrowerOnLoan → false for a borrower row whose user_id is NULL</li>
 *   <li>Two-org isolation: a link in ORG_A is NOT visible when context is ORG_B</li>
 *   <li>loanIdsForBorrower returns only that user's linked loans, org-scoped</li>
 *   <li>null userId → false / empty list (never matches)</li>
 * </ol>
 *
 * Distinct org UUIDs (3-hex suffix range a05/a06, no overlap with LoanAgentRlsIT a01/a02
 * or LoanAgentServiceIT a03/a04).
 */
class LoanLinkageServiceIT extends AbstractIntegrationTest {

    // Unique org pairs — no overlap with existing ITs
    static final UUID ORG_A = UUID.fromString("00000000-0000-0000-0000-000000000a05");
    static final UUID ORG_B = UUID.fromString("00000000-0000-0000-0000-000000000a06");

    @Autowired
    LoanLinkageResolver loanLinkageResolver;

    @Autowired
    JdbcTemplate jdbc;

    UUID loanA;   // loan in ORG_A
    UUID loanA2;  // second loan in ORG_A
    UUID loanB;   // loan in ORG_B

    UUID linkedUser;   // user linked to loanA and loanA2 as borrower in ORG_A
    UUID unlinkedUser; // user with no borrower_party row in ORG_A

    @BeforeEach
    void seed() {
        // Orgs (idempotent)
        for (Object[] row : new Object[][]{{ORG_A, "ll-svc-org-a"}, {ORG_B, "ll-svc-org-b"}}) {
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
            loanA, "LLSVC-A1-" + loanA.toString().substring(0, 8), UUID.randomUUID(), ORG_A);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanA2, "LLSVC-A2-" + loanA2.toString().substring(0, 8), UUID.randomUUID(), ORG_A);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanB, "LLSVC-B1-" + loanB.toString().substring(0, 8), UUID.randomUUID(), ORG_B);

        // Cognito subs
        linkedUser   = UUID.randomUUID();
        unlinkedUser = UUID.randomUUID();

        // Borrower in ORG_A on loanA — linked to linkedUser
        jdbc.update(
            "insert into borrower_party " +
            "(id,version,org_id,loan_id,is_primary,ordinal,user_id) " +
            "values (?,0,?::uuid,?,true,0,?)",
            UUID.randomUUID(), ORG_A, loanA, linkedUser);

        // Borrower in ORG_A on loanA2 — linked to linkedUser (second loan)
        jdbc.update(
            "insert into borrower_party " +
            "(id,version,org_id,loan_id,is_primary,ordinal,user_id) " +
            "values (?,0,?::uuid,?,true,0,?)",
            UUID.randomUUID(), ORG_A, loanA2, linkedUser);

        // Co-borrower on loanA in ORG_A — user_id NULL (no account)
        jdbc.update(
            "insert into borrower_party " +
            "(id,version,org_id,loan_id,is_primary,ordinal,user_id) " +
            "values (?,0,?::uuid,?,false,1,null)",
            UUID.randomUUID(), ORG_A, loanA);

        // Same linkedUser appears as a borrower in ORG_B on loanB (cross-org isolation probe)
        jdbc.update(
            "insert into borrower_party " +
            "(id,version,org_id,loan_id,is_primary,ordinal,user_id) " +
            "values (?,0,?::uuid,?,true,0,?)",
            UUID.randomUUID(), ORG_B, loanB, linkedUser);
    }

    // ── isBorrowerOnLoan ─────────────────────────────────────────────────────

    @Test
    void isBorrowerOnLoan_trueForLinkedUser() {
        TenantContextHolder.set(ORG_A);
        assertThat(loanLinkageResolver.isBorrowerOnLoan(loanA, linkedUser))
                .as("linkedUser is a borrower on loanA in ORG_A")
                .isTrue();
    }

    @Test
    void isBorrowerOnLoan_falseForUnlinkedUser() {
        TenantContextHolder.set(ORG_A);
        assertThat(loanLinkageResolver.isBorrowerOnLoan(loanA, unlinkedUser))
                .as("unlinkedUser has no borrower_party row on loanA in ORG_A")
                .isFalse();
    }

    @Test
    void isBorrowerOnLoan_falseForNullUserId() {
        TenantContextHolder.set(ORG_A);
        // null userId must never match (co-borrower without account defense)
        assertThat(loanLinkageResolver.isBorrowerOnLoan(loanA, null))
                .as("null userId must not match any borrower row")
                .isFalse();
    }

    @Test
    void isBorrowerOnLoan_twoOrgIsolation_orgBContextDoesNotMatchOrgALink() {
        // linkedUser IS a borrower on loanA in ORG_A, but the tenant context is ORG_B.
        // The @TenantId filter must scope the query to ORG_B → no match.
        TenantContextHolder.set(ORG_B);
        assertThat(loanLinkageResolver.isBorrowerOnLoan(loanA, linkedUser))
                .as("ORG_B context must not see ORG_A borrower_party rows (cross-org leak)")
                .isFalse();
    }

    // ── loanIdsForBorrower ───────────────────────────────────────────────────

    @Test
    void loanIdsForBorrower_returnsLinkedLoansInOrg() {
        TenantContextHolder.set(ORG_A);
        List<UUID> ids = loanLinkageResolver.loanIdsForBorrower(linkedUser);
        assertThat(ids)
                .as("linkedUser is a borrower on loanA and loanA2 in ORG_A")
                .containsExactlyInAnyOrder(loanA, loanA2);
    }

    @Test
    void loanIdsForBorrower_emptyForUnlinkedUser() {
        TenantContextHolder.set(ORG_A);
        assertThat(loanLinkageResolver.loanIdsForBorrower(unlinkedUser))
                .as("unlinkedUser has no borrower_party rows in ORG_A")
                .isEmpty();
    }

    @Test
    void loanIdsForBorrower_emptyForNullUserId() {
        TenantContextHolder.set(ORG_A);
        assertThat(loanLinkageResolver.loanIdsForBorrower(null))
                .as("null userId must return empty list")
                .isEmpty();
    }

    @Test
    void loanIdsForBorrower_twoOrgIsolation_orgBContextOnlySeesOrgBRows() {
        // linkedUser has 2 rows in ORG_A and 1 row in ORG_B.
        // With ORG_B context only loanB should surface.
        TenantContextHolder.set(ORG_B);
        List<UUID> ids = loanLinkageResolver.loanIdsForBorrower(linkedUser);
        assertThat(ids)
                .as("ORG_B context must see only ORG_B borrower_party rows")
                .containsExactly(loanB);
    }

    @Test
    void loanIdsForBorrower_distinctCollapsesDuplicateRowsOnSameLoan() {
        TenantContextHolder.set(ORG_A);
        // borrower_party has NO (loan_id, user_id) uniqueness (unlike loan_agent) — a borrower may
        // appear on the same loan more than once (distinct ordinals). Seed a SECOND linkedUser row on
        // loanA; the query's `distinct` must collapse it so loanA surfaces exactly once.
        jdbc.update(
            "insert into borrower_party " +
            "(id,version,org_id,loan_id,is_primary,ordinal,user_id) " +
            "values (?,0,?::uuid,?,false,2,?)",
            UUID.randomUUID(), ORG_A, loanA, linkedUser);

        List<UUID> ids = loanLinkageResolver.loanIdsForBorrower(linkedUser);
        assertThat(ids)
                .as("duplicate borrower rows on loanA must collapse to a single loanA (distinct)")
                .containsExactlyInAnyOrder(loanA, loanA2);
    }
}
