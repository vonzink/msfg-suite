package com.msfg.los.identity;

import com.msfg.los.loan.service.BorrowerUserLinker;
import com.msfg.los.loan.service.BorrowerUserLinker.LinkResult;
import com.msfg.los.platform.tenancy.TenantContextHolder;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service-layer IT for the verified-email borrower auto-link seam (Phase F T5a):
 * {@link BorrowerUserLinker} (interface in loan-core) implemented by the parties adapter.
 *
 * <p>This is the <strong>account-takeover</strong> defense — every branch of the security matrix is
 * asserted at the DB layer. Rows are seeded via the JDBC superuser (bypasses RLS for setup), then
 * tenant context is bound via {@link TenantContextHolder#set} so Hibernate's {@code @TenantId} filter
 * resolves correctly.
 *
 * <p>Matrix covered here (the email_verified=false and agent-role gates live one layer up, in
 * {@code UserAccountService} via {@code MeBorrowerLinkIT}):
 * <ul>
 *   <li>(b) zero email matches → NO_OP, no stamp</li>
 *   <li>(c) exactly one unlinked match → LINKED, stamped</li>
 *   <li>(d) >1 unlinked match → NO_OP, NONE stamped</li>
 *   <li>(e) pre-existing user_id (non-null) → never overwritten</li>
 *   <li>(f) cross-org: a matching email in another org is not stamped under the caller's tenant</li>
 *   <li>(g) idempotent re-call → still a single link, no error</li>
 *   <li>case-insensitive email match; null/blank inputs → NO_OP</li>
 * </ul>
 *
 * Distinct org UUIDs (a07/a08 — no overlap with LoanLinkageServiceIT a05/a06 or the agent ITs).
 */
class BorrowerUserLinkerIT extends AbstractIntegrationTest {

    static final UUID ORG_A = UUID.fromString("00000000-0000-0000-0000-000000000a07");
    static final UUID ORG_B = UUID.fromString("00000000-0000-0000-0000-000000000a08");

    @Autowired
    BorrowerUserLinker linker;

    @Autowired
    JdbcTemplate jdbc;

    UUID loanA;
    UUID loanB;

    @BeforeEach
    void seed() {
        for (Object[] row : new Object[][]{{ORG_A, "bul-org-a"}, {ORG_B, "bul-org-b"}}) {
            jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                row[0].toString(), row[1], row[1]);
        }

        loanA = UUID.randomUUID();
        loanB = UUID.randomUUID();
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanA, "BUL-A-" + loanA.toString().substring(0, 8), UUID.randomUUID(), ORG_A);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanB, "BUL-B-" + loanB.toString().substring(0, 8), UUID.randomUUID(), ORG_B);
    }

    /** Seeds a borrower_party row; returns its id. {@code userId} may be null (unlinked). */
    private UUID seedBorrower(UUID org, UUID loan, int ordinal, String email, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "insert into borrower_party " +
            "(id,version,org_id,loan_id,is_primary,ordinal,email,user_id) " +
            "values (?,0,?::uuid,?,?,?,?,?)",
            id, org, loan, ordinal == 0, ordinal, email, userId);
        return id;
    }

    private UUID userIdOf(UUID borrowerId) {
        return jdbc.queryForObject(
            "select user_id from borrower_party where id = ?", UUID.class, borrowerId);
    }

    // ── (c) exactly one unlinked match + verified → LINKED ──────────────────────

    @Test
    void linksWhenExactlyOneUnlinkedMatch() {
        UUID b = seedBorrower(ORG_A, loanA, 0, "borrower@example.com", null);
        UUID user = UUID.randomUUID();

        TenantContextHolder.set(ORG_A);
        assertThat(linker.linkByVerifiedEmail("borrower@example.com", user)).isEqualTo(LinkResult.LINKED);
        assertThat(userIdOf(b)).as("the single match is stamped").isEqualTo(user);
    }

    @Test
    void linkMatchesEmailCaseInsensitively() {
        UUID b = seedBorrower(ORG_A, loanA, 0, "Borrower@Example.com", null);
        UUID user = UUID.randomUUID();

        TenantContextHolder.set(ORG_A);
        assertThat(linker.linkByVerifiedEmail("borrower@example.COM", user)).isEqualTo(LinkResult.LINKED);
        assertThat(userIdOf(b)).isEqualTo(user);
    }

    // ── (b) zero matches → NO_OP ────────────────────────────────────────────────

    @Test
    void noOpWhenNoEmailMatch() {
        UUID b = seedBorrower(ORG_A, loanA, 0, "someoneelse@example.com", null);
        UUID user = UUID.randomUUID();

        TenantContextHolder.set(ORG_A);
        assertThat(linker.linkByVerifiedEmail("borrower@example.com", user)).isEqualTo(LinkResult.NO_OP);
        assertThat(userIdOf(b)).as("non-matching row untouched").isNull();
    }

    // ── (d) more than one unlinked match → NONE stamped ─────────────────────────

    @Test
    void noOpAndNoneStampedWhenMoreThanOneMatch() {
        UUID b1 = seedBorrower(ORG_A, loanA, 0, "dup@example.com", null);
        UUID b2 = seedBorrower(ORG_A, loanA, 1, "dup@example.com", null);
        UUID user = UUID.randomUUID();

        TenantContextHolder.set(ORG_A);
        assertThat(linker.linkByVerifiedEmail("dup@example.com", user)).isEqualTo(LinkResult.NO_OP);
        assertThat(userIdOf(b1)).as("ambiguous match #1 untouched").isNull();
        assertThat(userIdOf(b2)).as("ambiguous match #2 untouched").isNull();
    }

    // ── (e) pre-existing non-null user_id → never overwritten ────────────────────

    @Test
    void neverOverwritesAnAlreadyLinkedRow() {
        UUID prior = UUID.randomUUID();
        UUID b = seedBorrower(ORG_A, loanA, 0, "taken@example.com", prior);
        UUID attacker = UUID.randomUUID();

        TenantContextHolder.set(ORG_A);
        // Only candidate has the email but is already linked → not a candidate → NO_OP.
        assertThat(linker.linkByVerifiedEmail("taken@example.com", attacker)).isEqualTo(LinkResult.NO_OP);
        assertThat(userIdOf(b)).as("existing link is preserved (no takeover)").isEqualTo(prior);
    }

    @Test
    void linkedRowDoesNotCountTowardUniquenessSoSoleUnlinkedRowStillLinks() {
        // One row already linked (to someone), a second unlinked row shares the email.
        // The linked row is excluded from candidates → exactly ONE unlinked match → links it.
        UUID prior = UUID.randomUUID();
        UUID linked = seedBorrower(ORG_A, loanA, 0, "shared@example.com", prior);
        UUID unlinked = seedBorrower(ORG_A, loanA, 1, "shared@example.com", null);
        UUID user = UUID.randomUUID();

        TenantContextHolder.set(ORG_A);
        assertThat(linker.linkByVerifiedEmail("shared@example.com", user)).isEqualTo(LinkResult.LINKED);
        assertThat(userIdOf(linked)).as("pre-linked row preserved").isEqualTo(prior);
        assertThat(userIdOf(unlinked)).as("sole unlinked row gets stamped").isEqualTo(user);
    }

    // ── (f) cross-org isolation ─────────────────────────────────────────────────

    @Test
    void crossOrgMatchIsNotStampedUnderCallersTenant() {
        // The matching email lives in ORG_B; caller's tenant is ORG_A → no candidate → NO_OP.
        UUID bInB = seedBorrower(ORG_B, loanB, 0, "x-org@example.com", null);

        TenantContextHolder.set(ORG_A);
        UUID user = UUID.randomUUID();
        assertThat(linker.linkByVerifiedEmail("x-org@example.com", user)).isEqualTo(LinkResult.NO_OP);
        assertThat(userIdOf(bInB)).as("ORG_B row must not be touched by ORG_A caller").isNull();
    }

    // ── (g) idempotent re-call ──────────────────────────────────────────────────

    @Test
    void reCallIsIdempotentSingleLinkNoError() {
        UUID b = seedBorrower(ORG_A, loanA, 0, "idem@example.com", null);
        UUID user = UUID.randomUUID();

        TenantContextHolder.set(ORG_A);
        assertThat(linker.linkByVerifiedEmail("idem@example.com", user)).isEqualTo(LinkResult.LINKED);
        // Second call: the row is now linked (user_id IS NOT NULL) → excluded → NO_OP, no error.
        assertThat(linker.linkByVerifiedEmail("idem@example.com", user)).isEqualTo(LinkResult.NO_OP);
        assertThat(userIdOf(b)).as("still the single original link").isEqualTo(user);
    }

    // ── null / blank inputs ─────────────────────────────────────────────────────

    @Test
    void noOpForNullOrBlankInputs() {
        UUID b = seedBorrower(ORG_A, loanA, 0, "guard@example.com", null);
        TenantContextHolder.set(ORG_A);

        assertThat(linker.linkByVerifiedEmail(null, UUID.randomUUID())).isEqualTo(LinkResult.NO_OP);
        assertThat(linker.linkByVerifiedEmail("   ", UUID.randomUUID())).isEqualTo(LinkResult.NO_OP);
        assertThat(linker.linkByVerifiedEmail("guard@example.com", null)).isEqualTo(LinkResult.NO_OP);
        assertThat(userIdOf(b)).as("no guard input mutated the row").isNull();
    }

    // ── linkById — deterministic link for known borrower id (Phase A intake) ──

    /**
     * Happy path: freshly seeded unlinked row → LINKED on first call, NO_OP on second.
     * Resolution asserted via direct JDBC query (existsByLoanIdAndUserId equivalent).
     */
    @Test
    void linkById_linksUnlinkedRow() {
        UUID borrowerId = seedBorrower(ORG_A, loanA, 10, "intake@dev.local", null);
        UUID sub = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

        TenantContextHolder.set(ORG_A);
        assertThat(linker.linkById(borrowerId, sub))
                .as("first call stamps the row")
                .isEqualTo(LinkResult.LINKED);
        assertThat(userIdOf(borrowerId)).as("user_id written").isEqualTo(sub);
    }

    @Test
    void linkById_secondCallIsNoOp() {
        UUID borrowerId = seedBorrower(ORG_A, loanA, 11, "intake2@dev.local", null);
        UUID sub = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

        TenantContextHolder.set(ORG_A);
        linker.linkById(borrowerId, sub);
        assertThat(linker.linkById(borrowerId, sub))
                .as("idempotent re-call is NO_OP")
                .isEqualTo(LinkResult.NO_OP);
        assertThat(userIdOf(borrowerId)).as("link preserved").isEqualTo(sub);
    }

    @Test
    void linkById_nullInputsAreNoOp() {
        UUID borrowerId = seedBorrower(ORG_A, loanA, 12, "intake3@dev.local", null);
        TenantContextHolder.set(ORG_A);

        assertThat(linker.linkById(null, UUID.randomUUID())).isEqualTo(LinkResult.NO_OP);
        assertThat(linker.linkById(borrowerId, null)).isEqualTo(LinkResult.NO_OP);
        assertThat(userIdOf(borrowerId)).as("guard inputs left row unlinked").isNull();
    }

    @Test
    void linkById_resolvedOnLoan_afterLink() {
        UUID sub = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
        UUID borrowerId = seedBorrower(ORG_A, loanA, 13, "intake4@dev.local", null);

        TenantContextHolder.set(ORG_A);
        assertThat(linker.linkById(borrowerId, sub)).isEqualTo(LinkResult.LINKED);

        // Assert borrower is resolvable on the loan via direct JDBC (existsByLoanIdAndUserId equivalent).
        Boolean found = jdbc.queryForObject(
                "select exists(select 1 from borrower_party where loan_id = ? and user_id = ?)",
                Boolean.class, loanA, sub);
        assertThat(found).as("borrower is on the loan after linkById").isTrue();
    }
}
