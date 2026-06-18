package com.msfg.los.platform.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Provider-neutral view of the authenticated principal — the swappable seam between the app and
 * whatever identity provider is wired at the Spring Security filter layer.
 *
 * <p>The default implementation ({@link JwtPrincipalAdapter}) reads an OIDC/Cognito
 * {@code JwtAuthenticationToken} out of the {@code SecurityContext} and knows the Cognito claim
 * shape ({@code sub}, {@code email}, {@code name}/{@code given_name}+{@code family_name},
 * {@code org_id}). A non-Cognito IdP (Auth0, Keycloak, a custom token, …) supplies a different
 * {@code @Component} implementing this interface — no domain/service code changes.
 *
 * <p>{@link CurrentUser} is the convenient app-facing facade that delegates here; callers depend on
 * {@code CurrentUser}, not on this port directly. All accessors are read-only and never throw; an
 * absent / unauthenticated principal yields {@link Optional#empty()} (or an empty {@link Set} for
 * {@link #roles()}).
 */
public interface PrincipalPort {

    /** The stable principal identifier (Cognito {@code sub}, a UUID string), or empty if unauthenticated. */
    Optional<String> id();

    /** The principal's email, or empty if absent / unauthenticated. */
    Optional<String> email();

    /**
     * The principal's display name, or empty if absent / unauthenticated. The default adapter uses
     * the {@code name} claim, falling back to {@code given_name} + {@code family_name} joined.
     */
    Optional<String> name();

    /** The principal's tenant ({@code org_id}) as a UUID, or empty if absent / unparseable / unauthenticated. */
    Optional<UUID> orgId();

    /** The granted authorities as plain strings (e.g. {@code ROLE_LO}); empty if unauthenticated. */
    Set<String> roles();
}
