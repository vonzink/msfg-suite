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
 * DB-layer RLS isolation for the V18 Phase-1 tables: folder, document_type,
 * folder_template, document_status_history.
 *
 * Mirrors {@link DocumentsRlsIT}:
 *  - Two FRESH orgs (ORG_X / ORG_Y) used by no other test → exact counts.
 *  - Drops to 'app_user' via SET ROLE so superuser RLS bypass is eliminated.
 *  - Sets tenant GUC via select set_config('app.current_org', ?, false) per operation.
 *  - Asserts cross-org rows are invisible and fail-closed (RESET GUC → 0).
 *
 * Also asserts the V18 per-org seed produced exactly 17 folder_templates + 16 document_types
 * for the pre-existing MSFG org (00aa), which existed at migration time.
 */
class DocumentsPhase1RlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — no overlap with other RLS ITs (DocumentsRlsIT uses d7/d8)
    static final String ORG_X = "00000000-0000-0000-0000-0000000000e1";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000e2";

    UUID loanIdX;
    UUID loanIdY;

    @BeforeEach
    void seedOrgsAndLoans() {
        for (String[] o : new String[][]{{ORG_X, "org-docp1-x"}, {ORG_Y, "org-docp1-y"}})
            jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                o[0], o[1], o[1]);

        loanIdX = UUID.randomUUID();
        loanIdY = UUID.randomUUID();
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdX, "RLS-DOCP1-X-" + loanIdX.toString().substring(0, 8), UUID.randomUUID(), ORG_X);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdY, "RLS-DOCP1-Y-" + loanIdY.toString().substring(0, 8), UUID.randomUUID(), ORG_Y);
    }

    @Test
    void rlsIsolatesPhase1TablesByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }
            try {
                // ── ORG_X inserts: one row in each new table ─────────────────────────
                setOrg(c, ORG_X);

                // folder (root)
                UUID folderIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into folder (id,version,org_id,loan_id,parent_id,display_name,name_normalized) " +
                        "values (?,0,?::uuid,?,null,'Submission','submission')")) {
                    ps.setObject(1, folderIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // document_type
                try (var ps = c.prepareStatement(
                        "insert into document_type (id,version,org_id,name,slug,sort_order) " +
                        "values (?,0,?::uuid,'W-2','w-2',1)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, ORG_X);
                    ps.executeUpdate();
                }

                // folder_template
                try (var ps = c.prepareStatement(
                        "insert into folder_template (id,version,org_id,display_name,sort_key,sort_order) " +
                        "values (?,0,?::uuid,'Submission','01',1)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, ORG_X);
                    ps.executeUpdate();
                }

                // a document to anchor the status-history FK
                UUID docIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into document (id,version,org_id,loan_id,document_type,storage_key,document_status) " +
                        "values (?,0,?::uuid,?,'OTHER',?,'UPLOADED')")) {
                    ps.setObject(1, docIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.setString(4, "docs/rls/" + docIdX + ".pdf");
                    ps.executeUpdate();
                }

                // document_status_history
                try (var ps = c.prepareStatement(
                        "insert into document_status_history (id,version,org_id,document_id,status,transitioned_at) " +
                        "values (?,0,?::uuid,?,'UPLOADED',now())")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, ORG_X);
                    ps.setObject(3, docIdX);
                    ps.executeUpdate();
                }

                // ── Cross-org isolation: ORG_Y sees none of ORG_X's rows ─────────────
                assertThat(countTable(c, ORG_Y, "folder")).isZero();
                assertThat(countTable(c, ORG_Y, "document_type")).isZero();
                assertThat(countTable(c, ORG_Y, "folder_template")).isZero();
                assertThat(countTable(c, ORG_Y, "document_status_history")).isZero();

                // ── ORG_X sees exactly its own rows (fresh org → exact counts) ───────
                assertThat(countTable(c, ORG_X, "folder")).isEqualTo(1);
                assertThat(countTable(c, ORG_X, "document_type")).isEqualTo(1);
                assertThat(countTable(c, ORG_X, "folder_template")).isEqualTo(1);
                assertThat(countTable(c, ORG_X, "document_status_history")).isEqualTo(1);

                // ── Fail-closed: RESET GUC → deny-all on every new table ─────────────
                setOrg(c, null);
                for (String t : new String[]{"folder", "document_type", "folder_template", "document_status_history"}) {
                    try (var stx = c.createStatement();
                         var rs = stx.executeQuery("select count(*) from " + t)) {
                        rs.next();
                        assertThat(rs.getInt(1)).as("fail-closed on " + t).isZero();
                    }
                }
            } finally {
                try (var st = c.createStatement()) {
                    st.execute("reset role");
                }
            }
        }
    }

    @Test
    void v18SeedProduced17TemplatesAnd16TypesPerExistingOrg() throws Exception {
        // The MSFG org (00aa) existed at migration time, so V18 seeded it.
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }
            try {
                setOrg(c, DEFAULT_ORG);
                assertThat(countTable(c, DEFAULT_ORG, "folder_template")).isEqualTo(17);
                assertThat(countTable(c, DEFAULT_ORG, "document_type")).isEqualTo(16);

                // The flagged singletons are present exactly once.
                assertThat(scalar(c, "select count(*) from folder_template where is_delete_folder")).isEqualTo(1);
                assertThat(scalar(c, "select count(*) from folder_template where is_old_loan_archive")).isEqualTo(1);
                assertThat(scalar(c, "select count(*) from folder_template where display_name = 'Delete' and is_delete_folder")).isEqualTo(1);
                assertThat(scalar(c, "select count(*) from folder_template where display_name = 'Old Loan Files' and is_old_loan_archive")).isEqualTo(1);

                // The 'Other' catch-all type has the null default folder + 50MB cap.
                assertThat(scalar(c, "select count(*) from document_type where slug = 'other' and default_folder_name is null and max_file_size_bytes = 52428800")).isEqualTo(1);
            } finally {
                try (var st = c.createStatement()) {
                    st.execute("reset role");
                }
            }
        }
    }

    private int scalar(Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

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
            try (var ps = c.prepareStatement("select set_config('app.current_org', ?, false)")) {
                ps.setString(1, org);
                ps.execute();
            }
        }
    }
}
