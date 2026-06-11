package com.msfg.los.coc;

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
 * DB-layer RLS isolation for coc_draft and coc_history_entry tables.
 *
 * Mirrors the mechanism in AssetsLiabilitiesRlsIT (financials package):
 *  - Two FRESH orgs (ORG_X / ORG_Y) that no other test uses → exact counts.
 *  - Drops to 'app_user' via SET ROLE so superuser RLS bypass is eliminated.
 *  - Sets tenant GUC via select set_config('app.current_org', ?, false) per operation.
 *  - Asserts fail-closed (RESET GUC → count 0) on coc_draft.
 */
class CocRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — no overlap with any other RlsIT
    // (a1/a2, b1/b2, c1/c2, d1/d2, e1/e2, f1/f2 already taken)
    static final String ORG_X = "00000000-0000-0000-0000-0000000000c5";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000c6";

    // Loan IDs seeded by jdbc (superuser) — FK anchor for coc tables
    UUID loanIdX;
    UUID loanIdY;

    @BeforeEach
    void seedOrgsAndLoans() {
        // Seed orgs (idempotent — safe across test reruns)
        for (String[] o : new String[][]{{ORG_X, "org-coc-x"}, {ORG_Y, "org-coc-y"}})
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
            loanIdX, "RLS-COC-X-" + loanIdX.toString().substring(0, 8),
            UUID.randomUUID(), ORG_X);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdY, "RLS-COC-Y-" + loanIdY.toString().substring(0, 8),
            UUID.randomUUID(), ORG_Y);
    }

    @Test
    void rlsIsolatesCocDraftAndHistoryEntryByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            // Drop to non-superuser role — superusers bypass RLS even with FORCE
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // ── ORG_X inserts ────────────────────────────────────────────────────
                setOrg(c, ORG_X);

                // coc_draft (required NOT-NULL: id, org_id, loan_id; jsonb cols default to '[]')
                UUID draftIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into coc_draft (id,version,org_id,loan_id) " +
                        "values (?,0,?::uuid,?)")) {
                    ps.setObject(1, draftIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // coc_history_entry (required NOT-NULL: id, org_id, loan_id, status; jsonb cols default to '[]')
                UUID historyIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into coc_history_entry (id,version,org_id,loan_id,status) " +
                        "values (?,0,?::uuid,?,'PENDING')")) {
                    ps.setObject(1, historyIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // ── Cross-org isolation: ORG_Y sees nothing ──────────────────────────
                assertThat(countTable(c, ORG_Y, "coc_draft")).isZero();
                assertThat(countTable(c, ORG_Y, "coc_history_entry")).isZero();

                // ── ORG_X sees its own rows (exactly 1 each — orgs are fresh) ────────
                assertThat(countTable(c, ORG_X, "coc_draft")).isEqualTo(1);
                assertThat(countTable(c, ORG_X, "coc_history_entry")).isEqualTo(1);

                // ── Fail-closed: RESET GUC → count 0 (deny-all) ─────────────────────
                setOrg(c, null); // RESET app.current_org
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select count(*) from coc_draft")) {
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
