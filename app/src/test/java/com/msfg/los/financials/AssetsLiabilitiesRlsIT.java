package com.msfg.los.financials;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-layer RLS isolation for asset, liability, and asset_verification tables.
 *
 * Mirrors the mechanism in EmploymentIncomeRlsIT (income package):
 *  - Two FRESH orgs (ORG_X / ORG_Y) that no other test uses → exact counts.
 *  - Drops to 'app_user' via SET ROLE so superuser RLS bypass is eliminated.
 *  - Sets tenant GUC via select set_config('app.current_org', ?, false) per operation.
 *  - Asserts fail-closed (RESET GUC → count 0) on asset.
 */
class AssetsLiabilitiesRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — no overlap with RlsIT (c1/c2) or EmploymentIncomeRlsIT (d1/d2)
    static final String ORG_X = "00000000-0000-0000-0000-0000000000e1";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000e2";

    // Loan IDs seeded by jdbc (superuser) — FK anchor for borrower_party
    UUID loanIdX;
    UUID loanIdY;

    @BeforeEach
    void seedOrgsAndLoans() {
        // Seed orgs (idempotent — safe across test reruns)
        for (String[] o : new String[][]{{ORG_X, "org-assets-x"}, {ORG_Y, "org-assets-y"}})
            jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                o[0], o[1], o[1]);

        // Seed one loan per org via superuser (bypasses RLS — pure FK anchor)
        loanIdX = UUID.randomUUID();
        loanIdY = UUID.randomUUID();
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdX, "RLS-AST-X-" + loanIdX.toString().substring(0, 8),
            UUID.randomUUID(), ORG_X);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdY, "RLS-AST-Y-" + loanIdY.toString().substring(0, 8),
            UUID.randomUUID(), ORG_Y);
    }

    @Test
    void rlsIsolatesAssetLiabilityVerificationByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            // Drop to non-superuser role — superusers bypass RLS even with FORCE
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // ── ORG_X inserts ────────────────────────────────────────────────────
                setOrg(c, ORG_X);

                // borrower_party (FK to loan seeded above)
                UUID borrowerIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into borrower_party (id,version,org_id,loan_id,is_primary,ordinal) " +
                        "values (?,0,?::uuid,?,false,0)")) {
                    ps.setObject(1, borrowerIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // asset (FK to borrower_party; loan_id plain uuid — no FK constraint in schema)
                UUID assetIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into asset (id,version,org_id,loan_id,borrower_id,ordinal,asset_type) " +
                        "values (?,0,?::uuid,?,?,0,'CHECKING')")) {
                    ps.setObject(1, assetIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, UUID.randomUUID()); // loan_id plain uuid — no FK
                    ps.setObject(4, borrowerIdX);
                    ps.executeUpdate();
                }

                // liability (FK to borrower_party; NOT-NULL: liability_type + include_in_dti defaulted)
                UUID liabilityIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into liability (id,version,org_id,loan_id,borrower_id,ordinal,liability_type,include_in_dti) " +
                        "values (?,0,?::uuid,?,?,0,'INSTALLMENT',true)")) {
                    ps.setObject(1, liabilityIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, UUID.randomUUID()); // loan_id plain uuid — no FK
                    ps.setObject(4, borrowerIdX);
                    ps.executeUpdate();
                }

                // asset_verification (borrower_id nullable — no borrower FK required)
                UUID verificationIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into asset_verification (id,version,org_id,loan_id,verification_type,status) " +
                        "values (?,0,?::uuid,?,'VOA','ORDERED')")) {
                    ps.setObject(1, verificationIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, UUID.randomUUID()); // loan_id plain uuid — no FK
                    ps.executeUpdate();
                }

                // ── Cross-org isolation: ORG_Y sees nothing ──────────────────────────
                assertThat(countTable(c, ORG_Y, "asset")).isZero();
                assertThat(countTable(c, ORG_Y, "liability")).isZero();
                assertThat(countTable(c, ORG_Y, "asset_verification")).isZero();

                // ── ORG_X sees its own rows (exactly 1 each — orgs are fresh) ────────
                assertThat(countTable(c, ORG_X, "asset")).isEqualTo(1);
                assertThat(countTable(c, ORG_X, "liability")).isEqualTo(1);
                assertThat(countTable(c, ORG_X, "asset_verification")).isEqualTo(1);

                // ── Fail-closed: RESET GUC → count 0 (deny-all) ─────────────────────
                setOrg(c, null); // RESET app.current_org
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select count(*) from asset")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }

            } finally {
                // Return connection cleanly to pool
                try (var st = c.createStatement()) {
                    st.execute("reset role");
                }
            }
        }
    }

    /** Count all rows visible to the given org (sets GUC then queries). */
    private int countTable(Connection c, String org, String table) throws Exception {
        setOrg(c, org);
        try (var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void setOrg(Connection c, String org) throws Exception {
        if (org == null) {
            // RESET removes the GUC → current_setting returns NULL (missing_ok=true)
            // → NULL::uuid → NULL → policy predicate NULL → deny-all (fail-closed)
            try (var st = c.createStatement()) {
                st.execute("reset app.current_org");
            }
        } else {
            try (var ps = c.prepareStatement(
                    "select set_config('app.current_org', ?, false)")) {
                ps.setString(1, org);
                ps.execute();
            }
        }
    }
}
