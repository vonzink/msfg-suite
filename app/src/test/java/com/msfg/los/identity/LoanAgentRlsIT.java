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
 * DB-layer RLS isolation for the V24 {@code loan_agent} table + a schema-version guard.
 *
 * Mirrors ContactsRlsIT (the loan-scoped analog):
 *  - Two FRESH orgs (ORG_X / ORG_Y) that no other test uses → exact counts.
 *  - Drops to 'app_user' via SET ROLE so superuser RLS bypass is eliminated.
 *  - Sets tenant GUC via select set_config('app.current_org', ?, false) per operation.
 *  - Asserts fail-closed (RESET GUC → count 0).
 *  - Asserts WITH CHECK: inserting an ORG_X row while the GUC says ORG_Y is rejected.
 * Plus: confirms Flyway reached V24 (this migration applied).
 */
class LoanAgentRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — 3-hex suffixes, no overlap with the 2-hex suffixes other RlsITs use.
    static final String ORG_X = "00000000-0000-0000-0000-000000000a01";
    static final String ORG_Y = "00000000-0000-0000-0000-000000000a02";

    // Loan IDs seeded by jdbc (superuser) — realistic loan anchors for the loan-scoped table
    UUID loanIdX;
    UUID loanIdY;

    @BeforeEach
    void seedOrgsAndLoans() {
        for (String[] o : new String[][]{{ORG_X, "org-loanagent-x"}, {ORG_Y, "org-loanagent-y"}})
            jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                o[0], o[1], o[1]);

        loanIdX = UUID.randomUUID();
        loanIdY = UUID.randomUUID();
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdX, "RLS-AGT-X-" + loanIdX.toString().substring(0, 8),
            UUID.randomUUID(), ORG_X);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdY, "RLS-AGT-Y-" + loanIdY.toString().substring(0, 8),
            UUID.randomUUID(), ORG_Y);
    }

    @Test
    void flywayReachedV24() {
        // version is varchar (lexical), so don't max() it; assert the V24 row applied successfully
        // (this RLS test depends on the V24 loan_agent table).
        Integer v24applied = jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true and version = '24'",
            Integer.class);
        assertThat(v24applied).as("V24 migration applied").isEqualTo(1);

        // The migration sequence has advanced to at least V24 — assert a floor, not the exact head,
        // so this guard survives every future migration (V25, V26, …) rather than breaking on each.
        Integer numericHead = jdbc.queryForObject(
            "select max(version::int) from flyway_schema_history " +
            "where success = true and version ~ '^[0-9]+$'", Integer.class);
        assertThat(numericHead).as("Flyway numeric schema head").isGreaterThanOrEqualTo(24);
    }

    @Test
    void rlsIsolatesLoanAgentTableByOrg() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            // Drop to non-superuser role — superusers bypass RLS even with FORCE
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // ── ORG_X insert: one loan_agent row ─────────────────────────────────
                // NOT-NULLs without defaults: id, org_id, loan_id, user_id;
                // agent_role/ordinal/version have DEFAULTs but set explicitly here.
                setOrg(c, ORG_X);

                try (var ps = c.prepareStatement(
                        "insert into loan_agent (id,version,org_id,loan_id,user_id,agent_role,ordinal) " +
                        "values (?,0,?::uuid,?,?,'BUYERS_AGENT',0)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.setObject(4, UUID.randomUUID()); // user_id = a Cognito sub
                    ps.executeUpdate();
                }

                // ── (a) Cross-org isolation: ORG_Y sees nothing ──────────────────────
                assertThat(countAgents(c, ORG_Y)).as("ORG_Y view of loan_agent").isZero();

                // ── ORG_X sees its own row (exactly 1 — orgs are fresh) ──────────────
                assertThat(countAgents(c, ORG_X)).as("ORG_X view of loan_agent").isEqualTo(1);

                // ── (b) Fail-closed: RESET GUC → count 0 (deny-all) ──────────────────
                setOrg(c, null); // RESET app.current_org
                try (var st = c.createStatement();
                     var rs = st.executeQuery("select count(*) from loan_agent")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("fail-closed on loan_agent").isZero();
                }

                // ── (c) WITH CHECK: GUC says ORG_Y but the row claims ORG_X → rejected
                setOrg(c, ORG_Y);
                assertWithCheckRejected(c,
                        "insert into loan_agent (id,version,org_id,loan_id,user_id,agent_role,ordinal) " +
                        "values ('" + UUID.randomUUID() + "'::uuid,0,'" + ORG_X + "'::uuid,'"
                        + loanIdX + "'::uuid,'" + UUID.randomUUID() + "'::uuid,'LISTING_AGENT',0)");

                // Nothing slipped in: ORG_X still owns exactly 1 row.
                assertThat(countAgents(c, ORG_X))
                        .as("loan_agent row count after WITH CHECK probe").isEqualTo(1);

            } finally {
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
        }).as("WITH CHECK on loan_agent").hasMessageContaining("row-level security");
    }

    /** Count loan_agent rows visible to the given org (sets GUC then queries). */
    private int countAgents(Connection c, String org) throws Exception {
        setOrg(c, org);
        try (var st = c.createStatement();
             var rs = st.executeQuery("select count(*) from loan_agent")) {
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
