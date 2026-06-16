package com.msfg.los.platform.tenancy;
import com.msfg.los.platform.error.NotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;

@Component
public class TenantContext {
    public Optional<UUID> orgId() { return Optional.ofNullable(TenantContextHolder.get()); }
    /** The current tenant's org id, or a 404 NOT_FOUND if no tenant is bound to the request. */
    public UUID requireOrgId() { return orgId().orElseThrow(() -> new NotFoundException("Tenant", "current")); }
    public boolean isPlatformAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }
}
