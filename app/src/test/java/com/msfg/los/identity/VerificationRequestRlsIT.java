package com.msfg.los.identity;

import com.msfg.los.platform.crypto.OtpHasher;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-layer RLS isolation for the verification_request table (security spec §6.2/§9 V9). Mirrors
 * {@code ReoRlsIT}: two FRESH orgs, SET ROLE app_user (so superuser RLS bypass is eliminated), GUC set
 * per op, fail-closed on RESET. Proves an org-A staff context cannot read org-B's verification rows even
 * with a direct query — the Postgres backstop behind {@code @TenantId}.
 */
class VerificationRequestRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    static final String ORG_X = "00000000-0000-0000-0000-0000000000d7";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000d8";

    @BeforeEach
    void seedOrgs() {
        for (String[] o : new String[][]{{ORG_X, "org-verif-x"}, {ORG_Y, "org-verif-y"}})
            jdbc.update(
                    "insert into organization (id,version,name,slug,status,settings) "
                            + "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                    o[0], o[1], o[1]);
    }

    @Test
    void rlsIsolatesVerificationRequestsByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }
            try {
                setOrg(c, ORG_X);
                String salt = OtpHasher.newSalt();
                try (var ps = c.prepareStatement(
                        "insert into verification_request "
                                + "(id,version,org_id,loan_id,borrower_id,channel,code_hash,code_salt,"
                                + " expires_at,attempts,created_at,created_by) "
                                + "values (?,0,?::uuid,?,?,'EMAIL',?,?,?,0,?,?)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, ORG_X);
                    ps.setObject(3, UUID.randomUUID());
                    ps.setObject(4, UUID.randomUUID());
                    ps.setString(5, OtpHasher.hash("123456", salt));
                    ps.setString(6, salt);
                    ps.setTimestamp(7, Timestamp.from(Instant.now().plusSeconds(600)));
                    ps.setTimestamp(8, Timestamp.from(Instant.now()));
                    ps.setString(9, UUID.randomUUID().toString());
                    ps.executeUpdate();
                }

                // ORG_Y sees nothing; ORG_X sees exactly its own row.
                assertThat(count(c, ORG_Y)).isZero();
                assertThat(count(c, ORG_X)).isEqualTo(1);

                // Fail-closed: RESET GUC → deny-all.
                setOrg(c, null);
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select count(*) from verification_request")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            } finally {
                try (var st = c.createStatement()) {
                    st.execute("reset role");
                }
            }
        }
    }

    private int count(Connection c, String org) throws Exception {
        setOrg(c, org);
        try (var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from verification_request")) {
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
