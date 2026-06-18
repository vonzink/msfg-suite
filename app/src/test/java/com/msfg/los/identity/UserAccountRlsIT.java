package com.msfg.los.identity;

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
 * DB-layer RLS isolation for the V20 {@code user_account} table.
 *
 * Mirrors ContactsRlsIT:
 *  - Two FRESH orgs (ORG_X / ORG_Y) → exact counts.
 *  - Drops to 'app_user' via SET ROLE so superuser RLS bypass is eliminated.
 *  - Sets tenant GUC via set_config('app.current_org', ?, false) per operation.
 *  - Asserts cross-org isolation, fail-closed (RESET GUC → 0), and WITH CHECK on insert.
 */
class UserAccountRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — no overlap with any other RlsIT.
    static final String ORG_X = "00000000-0000-0000-0000-0000000000b9";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000ba";

    @BeforeEach
    void seedOrgs() {
        for (String[] o : new String[][]{{ORG_X, "org-user-x"}, {ORG_Y, "org-user-y"}})
            jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                o[0], o[1], o[1]);
    }

    @Test
    void rlsIsolatesUserAccountTableByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // ── ORG_X insert: one user row ──────────────────────────────────────
                setOrg(c, ORG_X);
                try (var ps = c.prepareStatement(
                        "insert into user_account (id,version,org_id,email,name,initials,role) " +
                        "values (?,0,?::uuid,?,?,?,?)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, ORG_X);
                    ps.setString(3, "probe@org-x.test");
                    ps.setString(4, "Rls Probe");
                    ps.setString(5, "RP");
                    ps.setString(6, "PROCESSOR");
                    ps.executeUpdate();
                }

                // ── cross-org isolation: ORG_Y sees nothing ─────────────────────────
                assertThat(countUsers(c, ORG_Y)).as("ORG_Y view of user_account").isZero();
                // ── ORG_X sees its own row ──────────────────────────────────────────
                assertThat(countUsers(c, ORG_X)).as("ORG_X view of user_account").isEqualTo(1);

                // ── fail-closed: RESET GUC → 0 ──────────────────────────────────────
                setOrg(c, null);
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select count(*) from user_account")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("fail-closed on user_account").isZero();
                }

                // ── WITH CHECK: GUC says ORG_Y but the row claims ORG_X → rejected ───
                setOrg(c, ORG_Y);
                assertWithCheckRejected(c,
                        "insert into user_account (id,version,org_id,email,name) " +
                        "values ('" + UUID.randomUUID() + "'::uuid,0,'" + ORG_X + "'::uuid," +
                        "'check@org-x.test','With Check Probe')");

                // nothing slipped in
                assertThat(countUsers(c, ORG_X))
                        .as("user_account row count after WITH CHECK probe").isEqualTo(1);
            } finally {
                try (var st = c.createStatement()) {
                    st.execute("reset role");
                }
            }
        }
    }

    private void assertWithCheckRejected(Connection c, String insertSql) {
        assertThatThrownBy(() -> {
            try (var st = c.createStatement()) {
                st.execute(insertSql);
            }
        }).as("WITH CHECK on user_account").hasMessageContaining("row-level security");
    }

    private int countUsers(Connection c, String org) throws Exception {
        setOrg(c, org);
        try (var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from user_account")) {
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
