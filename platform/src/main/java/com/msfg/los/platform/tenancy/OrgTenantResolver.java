package com.msfg.los.platform.tenancy;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class OrgTenantResolver implements CurrentTenantIdentifierResolver<UUID> {
    /** No tenant in context -> NIL -> @TenantId queries match no rows (fail-closed). */
    public static final UUID NIL = new UUID(0, 0);
    @Override public UUID resolveCurrentTenantIdentifier() {
        UUID org = TenantContextHolder.get();
        return org != null ? org : NIL;
    }
    @Override public boolean validateExistingCurrentSessions() { return false; }
}
