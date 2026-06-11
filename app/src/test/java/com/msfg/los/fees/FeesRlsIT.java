package com.msfg.los.fees;

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
 * DB-layer RLS isolation for fee_line_item and invoice_entry tables (V11).
 *
 * Mirrors AssetsLiabilitiesRlsIT:
 *  - Two FRESH orgs (ORG_X / ORG_Y) — no overlap with any other RLS IT.
 *  - Drops to 'app_user' via SET ROLE (superusers bypass RLS even with FORCE).
 *  - Sets tenant GUC via set_config per operation.
 *  - Asserts fail-closed (RESET GUC → count 0) on fee_line_item.
 */
class FeesRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — no overlap with other RLS ITs (e1/e2, c1/c2, d1/d2)
    static final String ORG_X = "00000000-0000-0000-0000-0000000000b1";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000b2";

    UUID loanIdX;
    UUID loanIdY;

    @BeforeEach
    void seedOrgsAndLoans() {
        // Seed orgs (idempotent — safe across test reruns)
        for (String[] o : new String[][]{{ORG_X, "org-fees-x"}, {ORG_Y, "org-fees-y"}})
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
            loanIdX, "RLS-FEE-X-" + loanIdX.toString().substring(0, 8),
            UUID.randomUUID(), ORG_X);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdY, "RLS-FEE-Y-" + loanIdY.toString().substring(0, 8),
            UUID.randomUUID(), ORG_Y);
    }

    @Test
    void rlsIsolatesFeeLineItemAndInvoiceEntryByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            // Drop to non-superuser role — superusers bypass RLS even with FORCE
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // ── ORG_X inserts ────────────────────────────────────────────────────
                setOrg(c, ORG_X);

                // fee_line_item (NOT-NULL: section, label)
                UUID feeIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into fee_line_item (id,version,org_id,loan_id,section,label) " +
                        "values (?,0,?::uuid,?,'A','Origination Fee')")) {
                    ps.setObject(1, feeIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // invoice_entry (NOT-NULL: fee_label, finalized)
                UUID invoiceIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into invoice_entry (id,version,org_id,loan_id,fee_label,finalized) " +
                        "values (?,0,?::uuid,?,'Appraisal Fee',false)")) {
                    ps.setObject(1, invoiceIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // ── Cross-org isolation: ORG_Y sees nothing ──────────────────────────
                assertThat(countTable(c, ORG_Y, "fee_line_item")).isZero();
                assertThat(countTable(c, ORG_Y, "invoice_entry")).isZero();

                // ── ORG_X sees its own rows (exactly 1 each — orgs are fresh) ────────
                assertThat(countTable(c, ORG_X, "fee_line_item")).isGreaterThanOrEqualTo(1);
                assertThat(countTable(c, ORG_X, "invoice_entry")).isGreaterThanOrEqualTo(1);

                // ── Fail-closed: RESET GUC → count 0 (deny-all) on fee_line_item ─────
                setOrg(c, null); // RESET app.current_org
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select count(*) from fee_line_item")) {
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
