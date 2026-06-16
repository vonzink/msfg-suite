package com.msfg.los.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fail-loud startup assertion that the live runtime datasource connects as a DB role that
 * Postgres Row Level Security actually constrains — i.e. a NON-SUPERUSER, NON-OWNER role
 * (the seeded {@code app_user}), per the deployment requirement documented in
 * {@code V4__app_role.sql}.
 *
 * <p>Why this matters: tenant isolation has two layers — the app-layer Hibernate {@code @TenantId}
 * filter (always active) and Postgres RLS (the DB-layer backstop). RLS only engages for a role that
 * does NOT bypass it. A PostgreSQL <b>superuser bypasses RLS unconditionally</b>; a table <b>owner</b>
 * is constrained only because every tenant table is declared {@code FORCE ROW LEVEL SECURITY}, and
 * the V4 deployment contract is to run the app as a non-owner role anyway (Flyway/DDL = owner,
 * runtime = {@code app_user}). If the app is deployed connecting as the owner/superuser, RLS is
 * silently dormant and this check makes that misconfiguration fail startup loudly instead of
 * shipping a false sense of DB-layer isolation.
 *
 * <p><b>Gating (must stay dormant by default):</b>
 * <ul>
 *   <li>{@code @Profile("!local & !test")} — never runs in the {@code local} dev profile or the
 *       integration-test profile (the Testcontainers ITs boot the app as the container's DB owner,
 *       so this check would otherwise break the whole IT suite).</li>
 *   <li>{@code los.security.enforce-rls-runtime-role} (default {@code false}) — the bean is absent
 *       unless a deployment explicitly opts in, so nothing breaks until the non-owner role is
 *       provisioned out-of-band (a Phase-6 ops step). dev/prod set this {@code true} once
 *       {@code app_user} has a LOGIN credential and the runtime datasource points at it.</li>
 * </ul>
 */
@Component
@Profile("!local & !test")
@ConditionalOnProperty(name = "los.security.enforce-rls-runtime-role", havingValue = "true")
public class RlsRuntimeRoleVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RlsRuntimeRoleVerifier.class);

    /** Representative FORCE-RLS tenant table whose ownership we check against the runtime role. */
    private static final String PROBE_TABLE = "loan";

    private final JdbcTemplate jdbc;

    public RlsRuntimeRoleVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        String currentUser = jdbc.queryForObject("select current_user", String.class);

        Boolean isSuperuser = jdbc.queryForObject(
                "select rolsuper from pg_roles where rolname = current_user", Boolean.class);
        if (Boolean.TRUE.equals(isSuperuser)) {
            throw new IllegalStateException(failureMessage(currentUser,
                    "the runtime role is a PostgreSQL SUPERUSER, which bypasses Row Level Security "
                            + "unconditionally"));
        }

        // The runtime role must not own the RLS tables: V4 mandates Flyway/DDL = owner,
        // runtime datasource = non-owner app_user. pg_class.relowner -> the table owner role.
        Boolean ownsProbeTable = jdbc.queryForObject(
                "select pg_catalog.pg_get_userbyid(c.relowner) = current_user "
                        + "from pg_catalog.pg_class c "
                        + "join pg_catalog.pg_namespace n on n.oid = c.relnamespace "
                        + "where c.relname = ? and n.nspname = 'public'",
                Boolean.class, PROBE_TABLE);
        if (Boolean.TRUE.equals(ownsProbeTable)) {
            throw new IllegalStateException(failureMessage(currentUser,
                    "the runtime role OWNS table '" + PROBE_TABLE + "' — run the app's datasource as a "
                            + "non-owner role (the seeded app_user) while Flyway runs as the owner"));
        }

        log.info("RLS runtime-role check passed: app connects as non-owner, non-superuser role '{}' "
                + "— Postgres RLS is engaged as the DB-layer tenant-isolation backstop.", currentUser);
    }

    private static String failureMessage(String currentUser, String reason) {
        return "REFUSING TO START: Row Level Security is enforced (los.security.enforce-rls-runtime-role=true) "
                + "but " + reason + ". Live DB role is '" + currentUser + "'. "
                + "Fix the deployment per V4__app_role.sql: run Flyway/DDL as the owner and point the app's "
                + "runtime datasource at the non-owner 'app_user' role (provision out-of-band: "
                + "ALTER ROLE app_user LOGIN PASSWORD '<secret>'; GRANT CONNECT ON DATABASE <db> TO app_user). "
                + "Alternatively set los.security.enforce-rls-runtime-role=false to fall back to app-layer "
                + "tenant isolation only (Hibernate @TenantId), accepting that the DB-layer RLS backstop is dormant.";
    }
}
