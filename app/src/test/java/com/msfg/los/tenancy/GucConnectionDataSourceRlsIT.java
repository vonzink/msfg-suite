package com.msfg.los.tenancy;

import com.msfg.los.platform.tenancy.TenantContextHolder;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the CONNECTION-LEVEL RLS GUC fix (option B): the app {@link DataSource} stamps the Postgres
 * session GUC {@code app.current_org} from {@link TenantContextHolder} at connection acquisition, so
 * EVERY statement — transactional or not — is tenant-scoped, and the value is reset when the
 * connection returns to the pool (no cross-tenant leak on reuse).
 *
 * <p>Unlike {@link RlsIT} (which sets the GUC MANUALLY via set_config to prove the policy), this IT
 * sets NO GUC by hand: it only sets {@code TenantContextHolder} and relies on the wrapped DataSource.
 * That is exactly the gap the borrower-portal reads hit — guard queries that run outside a
 * {@code @Transactional} boundary previously got no GUC and were hidden by fail-closed RLS.
 *
 * <p>Runs as the non-superuser {@code app_user} role (via SET ROLE) so RLS is actually enforced;
 * superusers bypass RLS even under FORCE ROW LEVEL SECURITY.
 */
class GucConnectionDataSourceRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Fresh orgs no other test touches, so counts are exact. (c7/c8 are unclaimed; d1/d2 collide
    // with EmploymentIncomeRlsIT, which seeds borrower_party children in the shared container.)
    static final UUID ORG_A = UUID.fromString("00000000-0000-0000-0000-0000000000c7");
    static final UUID ORG_B = UUID.fromString("00000000-0000-0000-0000-0000000000c8");

    @BeforeEach
    void seed() {
        // Seed as the container superuser (RLS bypassed) — deterministic fixtures, exactly one loan
        // per org. @BeforeEach re-runs per test method, so clear first to stay idempotent.
        for (UUID org : new UUID[]{ORG_A, ORG_B}) {
            jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                org.toString(), "org-" + org, "org-" + org);
            // Defensive: clear any child rows before the loan (these orgs are ours, but stay robust
            // against a future fixture collision rather than crash on an FK).
            jdbc.update("delete from borrower_party where org_id = ?::uuid", org.toString());
            jdbc.update("delete from loan where org_id = ?::uuid", org.toString());
        }
        insertLoanAsSuperuser(ORG_A, "9100000001");
        insertLoanAsSuperuser(ORG_B, "9100000002");
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    /**
     * The wrapper binds the CURRENT TenantContextHolder org to EACH connection checkout — proven by
     * two sequential checkouts under different orgs each carrying their own GUC. Deterministic: a
     * single leftover GUC value cannot satisfy both expectations, so this fails without the fix.
     */
    @Test
    void bindsCurrentOrgToEachConnectionCheckout() throws Exception {
        TenantContextHolder.set(ORG_A);
        try (Connection c = ds.getConnection()) {
            assertThat(currentOrg(c)).isEqualTo(ORG_A.toString());
        }

        TenantContextHolder.set(ORG_B);
        try (Connection c = ds.getConnection()) {
            assertThat(currentOrg(c)).isEqualTo(ORG_B.toString());
        }
    }

    /**
     * With only TenantContextHolder set (NO manual set_config), a query under app_user sees exactly
     * the caller's tenant rows — the borrower-portal read path that was failing. And when there is
     * no tenant context, the connection fails closed (empty GUC -> 0 rows), proving the reset on
     * release prevents a stale org leaking to the next checkout.
     */
    @Test
    void drivesRlsForLinkedTenantAndFailsClosedWithoutContext() throws Exception {
        TenantContextHolder.set(ORG_A);
        try (Connection c = ds.getConnection()) {
            asAppUser(c, () -> assertThat(countLoans(c)).isEqualTo(1));   // only ORG_A's loan
        }

        // No tenant context -> wrapper stamps empty -> fail-closed (and proves no leak of ORG_A).
        TenantContextHolder.clear();
        try (Connection c = ds.getConnection()) {
            asAppUser(c, () -> assertThat(countLoans(c)).isZero());
        }

        TenantContextHolder.set(ORG_B);
        try (Connection c = ds.getConnection()) {
            asAppUser(c, () -> assertThat(countLoans(c)).isEqualTo(1));   // only ORG_B's, never ORG_A's
        }
    }

    /**
     * The tenant binding must survive a transaction ROLLBACK on the same pooled checkout — the GUC is
     * a durable session setting, not tied to the business transaction. Guards against a regression
     * that ties the stamp to the transaction (e.g. is_local=true), which a ROLLBACK would revert,
     * resurrecting a prior tenant's org for the rest of the checkout.
     */
    @Test
    void stampSurvivesTransactionRollbackOnSameCheckout() throws Exception {
        TenantContextHolder.set(ORG_A);
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var st = c.createStatement()) {
                    st.execute("select 1");
                }
                c.rollback();
                assertThat(currentOrg(c)).isEqualTo(ORG_A.toString());
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private void insertLoanAsSuperuser(UUID org, String number) {
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?::uuid,0,?,?::uuid,?,?::uuid)",
            UUID.randomUUID().toString(), number, UUID.randomUUID().toString(), "STARTED", org.toString());
    }

    /**
     * Runs {@code body} on {@code c} under the non-superuser app_user role so RLS is enforced, then
     * resets the role so the pooled connection returns clean (Hikari does NOT reset SET ROLE; leaving
     * it set would poison the next test's superuser seed).
     */
    private static void asAppUser(Connection c, ThrowingRunnable body) throws Exception {
        try (var st = c.createStatement()) {
            st.execute("set role app_user");
        }
        try {
            body.run();
        } finally {
            try (var st = c.createStatement()) {
                st.execute("reset role");
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String currentOrg(Connection c) throws Exception {
        try (var st = c.createStatement();
             var rs = st.executeQuery("select current_setting('app.current_org', true)")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int countLoans(Connection c) throws Exception {
        try (var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from loan")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
