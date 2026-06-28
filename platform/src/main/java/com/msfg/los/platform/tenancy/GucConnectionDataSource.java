package com.msfg.los.platform.tenancy;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} decorator that binds the Postgres session GUC {@code app.current_org} —
 * which the Row Level Security policies read — to the tenant in {@link TenantContextHolder}, at the
 * moment a connection is checked out of the pool, for EVERY connection.
 *
 * <p><b>Why this exists.</b> The RLS policy is fail-closed:
 * {@code org_id = nullif(current_setting('app.current_org', true), '')::uuid} — an unset GUC matches
 * zero rows. Previously the GUC was only set by {@code TenantRlsAspect}, an {@code @Around} on
 * {@code @Transactional} methods (transaction-local, {@code is_local=true}). Any query that ran
 * OUTSIDE a transaction — notably the controller-layer access-guard linkage checks for the borrower
 * / agent portals ({@code isBorrowerOnLoan}, {@code loanIdsForBorrower}) — got NO GUC and was hidden
 * by RLS when the app connects as the non-owner {@code app_user} role in production. Setting the GUC
 * at connection acquisition covers transactional AND non-transactional statements uniformly, and is
 * decoupled from Hibernate's multitenancy machinery (it sits below it, so raw JDBC is covered too).
 *
 * <p><b>Pooling safety.</b> Isolation rests on a single invariant: the GUC is ALWAYS set on acquire
 * (session scope, {@code is_local=false}) — to the org, or to empty when there is no tenant context —
 * so a pooled connection can never inherit a previous caller's org, even if the prior checkout failed
 * to clean up. The reset to empty on release ({@link Connection#close()} / {@link Connection#abort}) is
 * a best-effort second layer, not the primary guarantee: it can be bypassed if a caller closes a
 * connection obtained via {@link Connection#unwrap}, and a failed reset is swallowed — both are safe
 * precisely because the next acquire re-stamps unconditionally.
 *
 * <p>Harmless when the app connects as a superuser/owner (dev/local/tests): superusers bypass RLS,
 * so the GUC is simply ignored; the value is still set, keeping behaviour identical across roles.
 */
public class GucConnectionDataSource implements DataSource {

    /** The custom GUC the RLS policies read. Session-scoped (is_local=false). */
    static final String SET_ORG = "select set_config('app.current_org', ?, false)";

    private final DataSource delegate;

    public GucConnectionDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return stampAndWrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return stampAndWrap(delegate.getConnection(username, password));
    }

    /**
     * Stamp the current tenant onto the freshly-acquired connection and return a proxy that resets
     * the GUC when the connection is released back to the pool. If stamping fails we close the raw
     * connection and propagate — better to fail the request than to serve it un-scoped.
     */
    private Connection stampAndWrap(Connection raw) throws SQLException {
        try {
            setOrg(raw, orgValue());
        } catch (SQLException | RuntimeException e) {
            try {
                raw.close();
            } catch (SQLException ignored) {
                // surfacing the original stamp failure is more useful
            }
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(
                GucConnectionDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ResetOnReleaseHandler(raw));
    }

    /** Current tenant id as text, or empty string when there is no tenant context (fail-closed). */
    private static String orgValue() {
        UUID org = TenantContextHolder.get();
        return org != null ? org.toString() : "";
    }

    private static void setOrg(Connection c, String value) throws SQLException {
        try (var ps = c.prepareStatement(SET_ORG)) {
            ps.setString(1, value);
            ps.execute();
        }
        // is_local=false is a SESSION set, but if the pool ever hands out autoCommit=false connections
        // (e.g. spring.datasource.hikari.auto-commit=false, or Hibernate's
        // connection.provider_disables_autocommit) the set runs inside an open transaction and a later
        // ROLLBACK would revert it to the connection's PRIOR value — potentially resurrecting another
        // tenant's org for the rest of the checkout. Commit it so the binding is durable regardless of
        // autoCommit config. No-op in the default (autoCommit=true) case, where the set already committed.
        if (!c.getAutoCommit()) {
            c.commit();
        }
    }

    /**
     * Delegates everything to the real connection except {@code close()}/{@code abort()}, which
     * clear the tenant GUC first so a connection never carries a stale org back into the pool.
     */
    private static final class ResetOnReleaseHandler implements InvocationHandler {
        private final Connection raw;

        ResetOnReleaseHandler(Connection raw) {
            this.raw = raw;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            // Identity semantics for Object methods (mirrors Spring's connection proxies): delegating
            // equals/hashCode to the raw connection would make equals asymmetric (proxy.equals(raw)
            // true but raw.equals(proxy) false) and could confuse pool bookkeeping.
            switch (name) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "GucConnection[" + raw + "]";
                case "close":
                case "abort":
                    clearOrgQuietly();
                    break;
                default:
                    break;
            }
            try {
                return method.invoke(raw, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void clearOrgQuietly() {
            try {
                if (!raw.isClosed()) {
                    setOrg(raw, "");
                }
            } catch (SQLException ignored) {
                // The connection is going back to the pool (or being aborted); the next checkout
                // re-stamps the correct org on acquire, so a failed reset cannot leak a tenant.
            }
        }
    }

    // --- pure delegation -----------------------------------------------------------------------

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return (T) this;
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
