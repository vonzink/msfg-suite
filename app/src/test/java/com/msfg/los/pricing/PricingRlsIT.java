package com.msfg.los.pricing;

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
 * DB-layer RLS isolation for rate_lock, pricing_adjustment and lock_event tables (V14).
 *
 * Mirrors FeesRlsIT:
 *  - Two FRESH orgs (ORG_X / ORG_Y) — no overlap with any other RLS IT.
 *  - Drops to 'app_user' via SET ROLE (superusers bypass RLS even with FORCE).
 *  - Sets tenant GUC via set_config per operation.
 *  - Asserts fail-closed (RESET GUC → count 0) on all three tables.
 *
 * Additionally proves the V14 grant difference:
 *  - lock_event is an append-only audit (SELECT/INSERT only): UPDATE and DELETE
 *    must fail with "permission denied" for app_user.
 *  - rate_lock UPDATE is permitted (positive control — the grant difference is real).
 */
class PricingRlsIT extends AbstractIntegrationTest {

    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;

    // Distinct UUIDs — no overlap with other RLS ITs (a1/a2, b1/b2, c1/c2, c5/c6, d1/d2, d7/d8, e1/e2, f1/f2)
    static final String ORG_X = "00000000-0000-0000-0000-0000000000f7";
    static final String ORG_Y = "00000000-0000-0000-0000-0000000000f8";

    UUID loanIdX;
    UUID loanIdY;

    @BeforeEach
    void seedOrgsAndLoans() {
        // Seed orgs (idempotent — safe across test reruns)
        for (String[] o : new String[][]{{ORG_X, "org-pricing-x"}, {ORG_Y, "org-pricing-y"}})
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
            loanIdX, "RLS-PRC-X-" + loanIdX.toString().substring(0, 8),
            UUID.randomUUID(), ORG_X);
        jdbc.update(
            "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
            "values (?,0,?,?,'STARTED',?::uuid)",
            loanIdY, "RLS-PRC-Y-" + loanIdY.toString().substring(0, 8),
            UUID.randomUUID(), ORG_Y);
    }

    @Test
    void rlsIsolatesPricingTablesByOrgAndLockEventIsAppendOnly() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);

            // Drop to non-superuser role — superusers bypass RLS even with FORCE
            try (var st = c.createStatement()) {
                st.execute("set role app_user");
            }

            try {
                // ── ORG_X inserts ────────────────────────────────────────────────────
                setOrg(c, ORG_X);

                // rate_lock (NOT-NULL: locked_rate, commitment_days, lock_date,
                //            expiration_date, extension_days_total, compensation_payer_type)
                UUID rateLockIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into rate_lock (id,version,org_id,loan_id,locked_rate,commitment_days," +
                        "lock_date,expiration_date,extension_days_total,compensation_payer_type) " +
                        "values (?,0,?::uuid,?,6.500,30,now(),current_date+30,0,'LENDER_PAID')")) {
                    ps.setObject(1, rateLockIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // pricing_adjustment (NOT-NULL: ordinal, name, row_type,
                //                     adjustment_percent, dollar_amount)
                UUID adjustmentIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into pricing_adjustment (id,version,org_id,loan_id,ordinal,name," +
                        "row_type,adjustment_percent,dollar_amount) " +
                        "values (?,0,?::uuid,?,1,'Base Price','BASE',-0.375,-1125.00)")) {
                    ps.setObject(1, adjustmentIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // lock_event (NOT-NULL: action, occurred_at, rate, commitment_days, expiration_date)
                UUID eventIdX = UUID.randomUUID();
                try (var ps = c.prepareStatement(
                        "insert into lock_event (id,version,org_id,loan_id,action,occurred_at," +
                        "rate,commitment_days,expiration_date) " +
                        "values (?,0,?::uuid,?,'CONTROL_YOUR_PRICE',now(),6.500,30,current_date+30)")) {
                    ps.setObject(1, eventIdX);
                    ps.setString(2, ORG_X);
                    ps.setObject(3, loanIdX);
                    ps.executeUpdate();
                }

                // ── Cross-org isolation: ORG_Y sees nothing ──────────────────────────
                assertThat(countTable(c, ORG_Y, "rate_lock")).isZero();
                assertThat(countTable(c, ORG_Y, "pricing_adjustment")).isZero();
                assertThat(countTable(c, ORG_Y, "lock_event")).isZero();

                // ── Fail-closed: RESET GUC → count 0 (deny-all) on all three ─────────
                setOrg(c, null); // RESET app.current_org
                for (String table : new String[]{"rate_lock", "pricing_adjustment", "lock_event"}) {
                    try (var st = c.createStatement();
                         var rs = st.executeQuery("select count(*) from " + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).as("fail-closed on %s", table).isZero();
                    }
                }

                // ── ORG_X still sees its own rows ────────────────────────────────────
                assertThat(countTable(c, ORG_X, "rate_lock")).isGreaterThanOrEqualTo(1);
                assertThat(countTable(c, ORG_X, "pricing_adjustment")).isGreaterThanOrEqualTo(1);
                assertThat(countTable(c, ORG_X, "lock_event")).isGreaterThanOrEqualTo(1);

                // ── lock_event is append-only: UPDATE + DELETE denied for app_user ───
                // (GUC is ORG_X from the counts above; autocommit → failed statements
                //  don't poison the connection)
                assertThatThrownBy(() -> {
                    try (var st = c.createStatement()) {
                        st.execute("update lock_event set actor = 'x'");
                    }
                }).hasMessageContaining("permission denied");

                assertThatThrownBy(() -> {
                    try (var st = c.createStatement()) {
                        st.execute("delete from lock_event");
                    }
                }).hasMessageContaining("permission denied");

                // ── Positive control: rate_lock UPDATE is granted (grant difference
                //    is real) — RLS limits the write to ORG_X's own rows ──────────────
                try (var st = c.createStatement()) {
                    int updated = st.executeUpdate("update rate_lock set locked_rate = 7.000");
                    assertThat(updated).isGreaterThanOrEqualTo(1);
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
