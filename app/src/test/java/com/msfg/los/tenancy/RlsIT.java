package com.msfg.los.tenancy;

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
 * Proves DB-layer RLS isolation independent of the app filter.
 *
 * Uses two FRESH orgs (ORG_X / ORG_Y) that no other test touches, so counts are exact.
 * Connects as the non-superuser 'app_user' role via SET ROLE: PostgreSQL superusers bypass
 * RLS even with FORCE ROW LEVEL SECURITY; FORCE only overrides the table-owner's implicit
 * bypass. SET ROLE drops to a non-superuser context that IS subject to the policy.
 */
class RlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    static final String ORG_X = "00000000-0000-0000-0000-0000000000c1";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000c2";

    @BeforeEach
    void seedOrgs() {
        for (String[] o : new String[][]{{ORG_X, "org-x"}, {ORG_Y, "org-y"}})
            jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                o[0], o[1], o[1]);
    }

    @Test
    void rlsIsolatesByOrgAndFailsClosed() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            // Drop to a non-superuser role so RLS is enforced.
            // PostgreSQL superusers bypass RLS even with FORCE; SET ROLE fixes that for this test.
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // Insert one loan per org (matching GUC → passes RLS WITH CHECK derived from USING)
                insertLoan(c, ORG_X, "9000000001");
                insertLoan(c, ORG_Y, "9000000002");

                // Each org sees only its own row (exactly 1 — ORG_X/ORG_Y are fresh, no other test uses them)
                assertThat(countLoans(c, ORG_X)).isEqualTo(1);
                assertThat(countLoans(c, ORG_Y)).isEqualTo(1);

                // Unset GUC (RESET) → current_setting('app.current_org', true) returns NULL
                // → NULL::uuid → NULL → policy `org_id = NULL` never true → deny-all (fail-closed)
                setOrg(c, null);
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select count(*) from loan")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            } finally {
                // Reset role so the connection returns cleanly to the pool
                try (var st = c.createStatement()) {
                    st.execute("reset role");
                }
            }
        }
    }

    private void insertLoan(Connection c, String org, String number) throws Exception {
        setOrg(c, org);
        try (var ps = c.prepareStatement(
                "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
                "values (?,0,?,?,?,?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, number);
            ps.setObject(3, UUID.randomUUID());
            ps.setString(4, "STARTED");
            ps.setObject(5, UUID.fromString(org));
            ps.executeUpdate();
        }
    }

    private int countLoans(Connection c, String org) throws Exception {
        setOrg(c, org);
        try (var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from loan")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void setOrg(Connection c, String org) throws Exception {
        if (org == null) {
            // RESET removes the custom GUC so current_setting('app.current_org', true) returns
            // NULL (missing_ok=true). NULL::uuid → NULL → policy `org_id = NULL` is NULL
            // (never true) → RLS denies all rows (fail-closed).
            // NOTE: passing NULL to set_config() via JDBC (ps.setString(1, null)) sets the GUC
            // to "" (empty string), and ""::uuid throws rather than returning false; so we use
            // the SQL-level RESET command which properly removes the GUC from the session.
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
