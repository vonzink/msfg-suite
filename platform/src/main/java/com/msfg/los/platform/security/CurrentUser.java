package com.msfg.los.platform.security;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * App-facing facade for the authenticated principal. Callers depend on this convenient type; the
 * actual principal source is the swappable {@link PrincipalPort} (default: {@link JwtPrincipalAdapter},
 * the Cognito/OIDC-JWT adapter). Swapping IdP changes only the injected port, not this facade or its
 * callers.
 */
@Component
public class CurrentUser {

    private final PrincipalPort principal;

    public CurrentUser(PrincipalPort principal) {
        this.principal = principal;
    }

    public Optional<String> id() {
        return principal.id();
    }

    public Optional<String> email() {
        return principal.email();
    }

    /**
     * The caller's display name from the principal: the {@code name} claim, else
     * {@code given_name} + {@code family_name} joined, else empty.
     */
    public Optional<String> name() {
        return principal.name();
    }

    /** The caller's tenant ({@code org_id}) as a UUID, or empty if absent / unparseable / unauthenticated. */
    public Optional<UUID> orgId() {
        return principal.orgId();
    }

    public Set<String> roles() {
        return principal.roles();
    }

    public boolean isAdmin() {
        return roles().contains(Role.ADMIN.authority());
    }
}
