package com.msfg.los.platform.tenancy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;

@Component
public class TenantContext {
    public Optional<UUID> orgId() { return Optional.ofNullable(TenantContextHolder.get()); }
    public boolean isPlatformAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
    }
}
