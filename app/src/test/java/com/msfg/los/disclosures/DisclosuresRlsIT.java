package com.msfg.los.disclosures;

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
 * DB-layer RLS isolation for the V17 Disclosures tables: disclosure_issuance
 * (current/versioned per kind) and disclosure_event (append-only audit).
 *
 * Mirrors AusRlsIT / PricingRlsIT:
 *  - Two FRESH orgs (ORG_X / ORG_Y) that no other test uses → exact counts.
 *  - Drops to 'app_user' via SET ROLE so superuser RLS bypass is eliminated.
 *  - Sets tenant GUC via select set_config('app.current_org', ?, false) per operation.
 *  - Asserts fail-closed (RESET GUC → count 0) on both tables.
 *  - Asserts WITH CHECK: inserting an ORG_X row while the GUC says ORG_Y is rejected.
 *  - Asserts the V17 grant difference (lock_event precedent): disclosure_event is an
 *    append-only audit (SELECT/INSERT only) → UPDATE + DELETE are denied for app_user,
 *    while disclosure_issuance UPDATE is granted (positive control).
 */
class DisclosuresRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — no overlap with any other RlsIT
    // (a1/a2, a5-a8, aa, b1/b2, bb, c1/c2, c5/c6, d1/d2, d7/d8, e1/e2, f1/f2, f7/f8 taken)
    static final String ORG_X = "00000000-0000-0000-0000-0000000000b5";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000b6";

    static final String[] TABLES = {"disclosure_issuance", "disclosure_event"};

    // Loan IDs seeded by jdbc (superuser) — realistic loan anchors for the loan-scoped tables
    UUID loanIdX;
    UUID loanIdY;

    @BeforeEach
    void seedOrgsAndLoans() {
        // Seed orgs (idempotent — safe across test reruns)
        for (String[] o : new String[][]{{ORG_X, "org-disc-x"}, {ORG_Y, "org-disc-y"}})
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
            loanIdX, "RLS-DISC-X-" + loanIdX.toString().substring(0, 8),
            UUID.randomUUID(), ORG_X);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdY, "RLS-DISC-Y-" + loanIdY.toString().substring(0, 8),
            UUID.randomUUID(), ORG_Y);
    }

    @Test
    void rlsIsolatesDisclosureTablesByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            // Drop to non-superuser role — superusers bypass RLS even with FORCE
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // ── ORG_X inserts: one row per table ─────────────────────────────────
                setOrg(c, ORG_X);

                // disclosure_issuance (NOT-NULL: id, org_id, loan_id, kind, status;
                //   disclosure_version + booleans + jsonb cols + version come from defaults)
                try (var ps = c.prepareStatement(
                        "insert into disclosure_issuance (id,org_id,loan_id,kind,status) " +
                        "values (?,?::uuid,?,'LE','ISSUED')")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // disclosure_event (NOT-NULL: id, org_id, loan_id, event_type;
                //   detail jsonb + occurred_at + version come from defaults)
                try (var ps = c.prepareStatement(
                        "insert into disclosure_event (id,org_id,loan_id,event_type) " +
                        "values (?,?::uuid,?,'ISSUED')")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // ── (a) Cross-org isolation: ORG_Y sees nothing ──────────────────────
                for (String table : TABLES) {
                    assertThat(countTable(c, ORG_Y, table)).as("ORG_Y view of %s", table).isZero();
                }

                // ── ORG_X sees its own rows (exactly 1 each — orgs are fresh) ────────
                for (String table : TABLES) {
                    assertThat(countTable(c, ORG_X, table)).as("ORG_X view of %s", table).isEqualTo(1);
                }

                // ── (b) Fail-closed: RESET GUC → count 0 (deny-all) on both ──────────
                setOrg(c, null); // RESET app.current_org
                for (String table : TABLES) {
                    try (var st = c.createStatement();
                         var rs = st.executeQuery("select count(*) from " + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).as("fail-closed on %s", table).isZero();
                    }
                }

                // ── (c) WITH CHECK: GUC says ORG_Y but the row claims ORG_X → rejected
                //    (autocommit → failed statements don't poison the connection).
                setOrg(c, ORG_Y);

                assertWithCheckRejected(c,
                        "insert into disclosure_issuance (id,org_id,loan_id,kind,status) " +
                        "values ('" + UUID.randomUUID() + "'::uuid,'" + ORG_X + "'::uuid,'"
                        + loanIdX + "'::uuid,'CD','ISSUED')",
                        "disclosure_issuance");
                assertWithCheckRejected(c,
                        "insert into disclosure_event (id,org_id,loan_id,event_type) " +
                        "values ('" + UUID.randomUUID() + "'::uuid,'" + ORG_X + "'::uuid,'"
                        + loanIdX + "'::uuid,'REVISED')",
                        "disclosure_event");

                // Nothing slipped in: ORG_X still owns exactly 1 row per table.
                for (String table : TABLES) {
                    assertThat(countTable(c, ORG_X, table))
                            .as("%s row count after WITH CHECK probes", table).isEqualTo(1);
                }

                // ── (d) disclosure_event is append-only: UPDATE + DELETE denied ──────
                //    (V17 granted SELECT,INSERT only — lock_event precedent).
                //    GUC is ORG_X from the counts above; autocommit → failed statements
                //    don't poison the connection.
                assertThatThrownBy(() -> {
                    try (var st = c.createStatement()) {
                        st.execute("update disclosure_event set actor = 'x'");
                    }
                }).as("disclosure_event UPDATE denied").hasMessageContaining("permission denied");

                assertThatThrownBy(() -> {
                    try (var st = c.createStatement()) {
                        st.execute("delete from disclosure_event");
                    }
                }).as("disclosure_event DELETE denied").hasMessageContaining("permission denied");

                // ── Positive control: disclosure_issuance UPDATE is granted (the grant
                //    difference is real) — RLS limits the write to ORG_X's own rows ────
                try (var st = c.createStatement()) {
                    int updated = st.executeUpdate("update disclosure_issuance set status = 'DELIVERED'");
                    assertThat(updated).as("disclosure_issuance UPDATE granted").isGreaterThanOrEqualTo(1);
                }

            } finally {
                // Return connection cleanly to pool
                try (var st = c.createStatement()) {
                    st.execute("reset role");
                }
            }
        }
    }

    /** The INSERT must die on the RLS policy — row never lands. */
    private void assertWithCheckRejected(Connection c, String insertSql, String table) {
        assertThatThrownBy(() -> {
            try (var st = c.createStatement()) {
                st.execute(insertSql);
            }
        }).as("WITH CHECK on %s", table).hasMessageContaining("row-level security");
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
