package com.msfg.los.documents;

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
 * DB-layer RLS isolation for document and document_content tables.
 *
 * Mirrors the mechanism in AssetsLiabilitiesRlsIT (financials package):
 *  - Two FRESH orgs (ORG_X / ORG_Y) that no other test uses → exact counts.
 *  - Drops to 'app_user' via SET ROLE so superuser RLS bypass is eliminated.
 *  - Sets tenant GUC via select set_config('app.current_org', ?, false) per operation.
 *  - Asserts fail-closed (RESET GUC → count 0) on document.
 *  - Proves document_content bytes are org-isolated (ORG_Y sees 0 content rows).
 */
class DocumentsRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — no overlap with other RLS ITs (a1/a2, b1/b2, c5/c6, d1/d2, e1/e2, f1/f2)
    static final String ORG_X = "00000000-0000-0000-0000-0000000000d7";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000d8";

    // Loan IDs seeded by jdbc (superuser) — FK anchor for document.loan_id
    UUID loanIdX;
    UUID loanIdY;

    @BeforeEach
    void seedOrgsAndLoans() {
        // Seed orgs (idempotent — safe across test reruns)
        for (String[] o : new String[][]{{ORG_X, "org-docs-x"}, {ORG_Y, "org-docs-y"}})
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
            loanIdX, "RLS-DOC-X-" + loanIdX.toString().substring(0, 8),
            UUID.randomUUID(), ORG_X);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdY, "RLS-DOC-Y-" + loanIdY.toString().substring(0, 8),
            UUID.randomUUID(), ORG_Y);
    }

    @Test
    void rlsIsolatesDocumentAndContentByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            // Drop to non-superuser role — superusers bypass RLS even with FORCE
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // ── ORG_X inserts ────────────────────────────────────────────────────
                setOrg(c, ORG_X);

                // document (NOT-NULL: loan_id, document_type, storage_key)
                UUID documentIdX = UUID.randomUUID();
                String storageKeyX = "docs/rls-test/" + documentIdX + ".pdf";
                try (var ps = c.prepareStatement(
                        "insert into document (id,version,org_id,loan_id,document_type,storage_key) " +
                        "values (?,0,?::uuid,?,'PAYSTUB',?)")) {
                    ps.setObject(1, documentIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.setString(4, storageKeyX);
                    ps.executeUpdate();
                }

                // document_content (NOT-NULL: storage_key; content bytea is nullable — pass small bytes)
                UUID contentIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into document_content (id,version,org_id,storage_key,content) " +
                        "values (?,0,?::uuid,?,decode('48656c6c6f','hex'))")) {
                    ps.setObject(1, contentIdX);
                    ps.setString(2, ORG_X);
                    ps.setString(3, storageKeyX);
                    ps.executeUpdate();
                }

                // ── Cross-org isolation: ORG_Y sees nothing ──────────────────────────
                assertThat(countTable(c, ORG_Y, "document")).isZero();
                assertThat(countTable(c, ORG_Y, "document_content")).isZero();

                // ── ORG_X sees its own rows (exactly 1 each — orgs are fresh) ────────
                assertThat(countTable(c, ORG_X, "document")).isEqualTo(1);
                assertThat(countTable(c, ORG_X, "document_content")).isEqualTo(1);

                // ── Fail-closed: RESET GUC → count 0 (deny-all) ─────────────────────
                setOrg(c, null); // RESET app.current_org
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select count(*) from document")) {
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
