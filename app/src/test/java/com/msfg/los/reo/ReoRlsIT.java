package com.msfg.los.reo;

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
 * DB-layer RLS isolation for the reo table.
 *
 * Mirrors the mechanism in AssetsLiabilitiesRlsIT (financials package):
 *  - Two FRESH orgs (ORG_X / ORG_Y) that no other test uses → exact counts.
 *  - Drops to 'app_user' via SET ROLE so superuser RLS bypass is eliminated.
 *  - Sets tenant GUC via select set_config('app.current_org', ?, false) per operation.
 *  - Asserts fail-closed (RESET GUC → count 0).
 *
 * Note: reo.loan_id is a plain uuid with no FK constraint (confirmed in V9 migration),
 * so a random UUID suffices as the loan_id — no loan row needs to be seeded.
 */
class ReoRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — no overlap with other RLS tests (c1/c2, d1/d2, e1/e2)
    static final String ORG_X = "00000000-0000-0000-0000-0000000000f1";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000f2";

    @BeforeEach
    void seedOrgs() {
        // Seed orgs (idempotent — safe across test reruns)
        for (String[] o : new String[][]{{ORG_X, "org-reo-x"}, {ORG_Y, "org-reo-y"}})
            jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                o[0], o[1], o[1]);
    }

    @Test
    void rlsIsolatesReoByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            // Drop to non-superuser role — superusers bypass RLS even with FORCE
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // ── ORG_X insert ─────────────────────────────────────────────────────
                setOrg(c, ORG_X);

                UUID reoIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into reo (id,version,org_id,loan_id,ordinal,is_subject_property) " +
                        "values (?,0,?::uuid,?,0,false)")) {
                    ps.setObject(1, reoIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, UUID.randomUUID()); // loan_id plain uuid — no FK
                    ps.executeUpdate();
                }

                // ── Cross-org isolation: ORG_Y sees nothing ──────────────────────────
                assertThat(countReo(c, ORG_Y)).isZero();

                // ── ORG_X sees its own row (exactly 1 — org is fresh) ────────────────
                assertThat(countReo(c, ORG_X)).isEqualTo(1);

                // ── Fail-closed: RESET GUC → count 0 (deny-all) ─────────────────────
                setOrg(c, null); // RESET app.current_org
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select count(*) from reo")) {
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

    /** Count all reo rows visible to the given org (sets GUC then queries). */
    private int countReo(Connection c, String org) throws Exception {
        setOrg(c, org);
        try (var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from reo")) {
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
