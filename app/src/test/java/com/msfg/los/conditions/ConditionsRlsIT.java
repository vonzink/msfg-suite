package com.msfg.los.conditions;

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
 * DB-layer RLS isolation for the V21 {@code loan_condition} table.
 *
 * Mirrors ContactsRlsIT:
 *  - Two FRESH orgs (ORG_X / ORG_Y) that no other test uses → exact counts.
 *  - Drops to 'app_user' via SET ROLE so superuser RLS bypass is eliminated.
 *  - Sets tenant GUC via select set_config('app.current_org', ?, false) per operation.
 *  - Asserts fail-closed (RESET GUC → count 0).
 *  - Asserts WITH CHECK: inserting an ORG_X row while the GUC says ORG_Y is rejected.
 */
class ConditionsRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — no overlap with any other RlsIT (contacts uses a7/a8).
    static final String ORG_X = "00000000-0000-0000-0000-0000000000a9";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000b9";

    UUID loanIdX;
    UUID loanIdY;

    @BeforeEach
    void seedOrgsAndLoans() {
        for (String[] o : new String[][]{{ORG_X, "org-conditions-x"}, {ORG_Y, "org-conditions-y"}})
            jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                o[0], o[1], o[1]);

        loanIdX = UUID.randomUUID();
        loanIdY = UUID.randomUUID();
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdX, "RLS-COND-X-" + loanIdX.toString().substring(0, 8),
            UUID.randomUUID(), ORG_X);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdY, "RLS-COND-Y-" + loanIdY.toString().substring(0, 8),
            UUID.randomUUID(), ORG_Y);
    }

    @Test
    void rlsIsolatesLoanConditionTableByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            // Drop to non-superuser role — superusers bypass RLS even with FORCE
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // ── ORG_X insert: one condition row ──────────────────────────────────
                // NOT-NULLs without defaults: id, org_id, loan_id, condition_text;
                // status has a default but set explicitly for clarity.
                setOrg(c, ORG_X);

                try (var ps = c.prepareStatement(
                        "insert into loan_condition (id,version,org_id,loan_id,condition_text,status) " +
                        "values (?,0,?::uuid,?,'Rls probe condition','Outstanding')")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // ── (a) Cross-org isolation: ORG_Y sees nothing ──────────────────────
                assertThat(count(c, ORG_Y)).as("ORG_Y view of loan_condition").isZero();

                // ── ORG_X sees its own row (exactly 1 — orgs are fresh) ──────────────
                assertThat(count(c, ORG_X)).as("ORG_X view of loan_condition").isEqualTo(1);

                // ── (b) Fail-closed: RESET GUC → count 0 (deny-all) ──────────────────
                setOrg(c, null);
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select count(*) from loan_condition")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("fail-closed on loan_condition").isZero();
                }

                // ── (c) WITH CHECK: GUC says ORG_Y but the row claims ORG_X → rejected
                setOrg(c, ORG_Y);
                assertWithCheckRejected(c,
                        "insert into loan_condition (id,version,org_id,loan_id,condition_text,status) " +
                        "values ('" + UUID.randomUUID() + "'::uuid,0,'" + ORG_X + "'::uuid,'"
                        + loanIdX + "'::uuid,'With check probe','Outstanding')");

                // Nothing slipped in: ORG_X still owns exactly 1 row.
                assertThat(count(c, ORG_X))
                        .as("loan_condition row count after WITH CHECK probe").isEqualTo(1);

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
        }).as("WITH CHECK on loan_condition").hasMessageContaining("row-level security");
    }

    private int count(Connection c, String org) throws Exception {
        setOrg(c, org);
        try (var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from loan_condition")) {
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
