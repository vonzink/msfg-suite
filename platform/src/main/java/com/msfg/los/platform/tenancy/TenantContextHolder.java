package com.msfg.los.platform.tenancy;
import java.util.UUID;
public final class TenantContextHolder {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private TenantContextHolder() {}
    public static void set(UUID orgId) { CURRENT.set(orgId); }
    public static UUID get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
