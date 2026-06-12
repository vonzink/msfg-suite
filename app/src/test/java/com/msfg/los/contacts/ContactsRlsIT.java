package com.msfg.los.contacts;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-layer RLS isolation for the V16 {@code contact} table.
 *
 * Mirrors AusRlsIT:
 *  - Two FRESH orgs (ORG_X / ORG_Y) that no other test uses → exact counts.
 *  - Drops to 'app_user' via SET ROLE so superuser RLS bypass is eliminated.
 *  - Sets tenant GUC via select set_config('app.current_org', ?, false) per operation.
 *  - Asserts fail-closed (RESET GUC → count 0).
 *  - Asserts WITH CHECK: inserting an ORG_X row while the GUC says ORG_Y is rejected.
 */
class ContactsRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — no overlap with any other RlsIT
    // (a1/a2, a5/a6, b1/b2, c1/c2, c5/c6, d1/d2, d7/d8, e1/e2, f1/f2, f7/f8 already taken)
    static final String ORG_X = "00000000-0000-0000-0000-0000000000a7";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000a8";

    // Loan IDs seeded by jdbc (superuser) — realistic loan anchors for the loan-scoped table
    UUID loanIdX;
    UUID loanIdY;

    @BeforeEach
    void seedOrgsAndLoans() {
        // Seed orgs (idempotent — safe across test reruns)
        for (String[] o : new String[][]{{ORG_X, "org-contacts-x"}, {ORG_Y, "org-contacts-y"}})
            jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                o[0], o[1], o[1]);

        // Seed one loan per org via superuser (bypasses RLS — pure anchor)
        loanIdX = UUID.randomUUID();
        loanIdY = UUID.randomUUID();
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdX, "RLS-CON-X-" + loanIdX.toString().substring(0, 8),
            UUID.randomUUID(), ORG_X);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdY, "RLS-CON-Y-" + loanIdY.toString().substring(0, 8),
            UUID.randomUUID(), ORG_Y);
    }

    @Test
    void rlsIsolatesContactTableByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            // Drop to non-superuser role — superusers bypass RLS even with FORCE
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // ── ORG_X insert: one contact row ────────────────────────────────────
                // NOT-NULLs without defaults: id, org_id, loan_id, role, name;
                // ordinal set explicitly (NOT NULL DEFAULT 0).
                setOrg(c, ORG_X);

                try (var ps = c.prepareStatement(
                        "insert into contact (id,version,org_id,loan_id,role,name,ordinal) " +
                        "values (?,0,?::uuid,?,'ESCROW_OFFICER','Rls Probe',0)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // ── (a) Cross-org isolation: ORG_Y sees nothing ──────────────────────
                assertThat(countContacts(c, ORG_Y)).as("ORG_Y view of contact").isZero();

                // ── ORG_X sees its own row (exactly 1 — orgs are fresh) ──────────────
                assertThat(countContacts(c, ORG_X)).as("ORG_X view of contact").isEqualTo(1);

                // ── (b) Fail-closed: RESET GUC → count 0 (deny-all) ──────────────────
                setOrg(c, null); // RESET app.current_org
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select count(*) from contact")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("fail-closed on contact").isZero();
                }

                // ── (c) WITH CHECK: GUC says ORG_Y but the row claims ORG_X → rejected
                //    (autocommit → failed statement doesn't poison the connection).
                setOrg(c, ORG_Y);
                assertWithCheckRejected(c,
                        "insert into contact (id,version,org_id,loan_id,role,name,ordinal) " +
                        "values ('" + UUID.randomUUID() + "'::uuid,0,'" + ORG_X + "'::uuid,'"
                        + loanIdX + "'::uuid,'ATTORNEY','With Check Probe',0)");

                // Nothing slipped in: ORG_X still owns exactly 1 row.
                assertThat(countContacts(c, ORG_X))
                        .as("contact row count after WITH CHECK probe").isEqualTo(1);

            } finally {
                // Return connection cleanly to pool
                try (var st = c.createStatement()) {
                    st.execute("reset role");
                }
            }
        }
    }

    /** The INSERT must die on the RLS policy — row never lands. */
    private void assertWithCheckRejected(Connection c, String insertSql) {
        assertThatThrownBy(() -> {
            try (var st = c.createStatement()) {
                st.execute(insertSql);
            }
        }).as("WITH CHECK on contact").hasMessageContaining("row-level security");
    }

    /** Count contact rows visible to the given org (sets GUC then queries). */
    private int countContacts(Connection c, String org) throws Exception {
        setOrg(c, org);
        try (var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from contact")) {
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
