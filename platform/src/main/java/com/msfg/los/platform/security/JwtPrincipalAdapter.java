package com.msfg.los.platform.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * OIDC-JWT / Cognito implementation of {@link PrincipalPort}: reads the
 * {@code JwtAuthenticationToken} placed in the {@code SecurityContext} by the Spring Security
 * resource-server filter chain (wired in {@code app/config}: {@code SecurityConfig} +
 * {@code OrgScopedJwtAuthenticationConverter} + {@code CognitoRolesConverter}).
 *
 * <p>This is the <strong>one place</strong> that knows the IdP's claim shape — the Cognito claim
 * names are the constants below. Swapping IdP means a different {@code PrincipalPort} adapter; no
 * domain/service code (which depends on {@link CurrentUser}) changes.
 *
 * <p>Behaviour is intentionally identical to the pre-port {@code CurrentUser} (this class lifted its
 * extraction logic verbatim): {@code id} = subject, {@code email} = {@value #CLAIM_EMAIL} claim,
 * {@code name} = {@value #CLAIM_NAME} claim else {@value #CLAIM_GIVEN_NAME} + {@value #CLAIM_FAMILY_NAME}
 * joined, {@code roles} = authority strings.
 */
@Component
public class JwtPrincipalAdapter implements PrincipalPort {

    /** Cognito/OIDC claim names. The single source of truth for this IdP's token shape. */
    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_NAME = "name";
    static final String CLAIM_GIVEN_NAME = "given_name";
    static final String CLAIM_FAMILY_NAME = "family_name";
    static final String CLAIM_ORG_ID = "org_id";
    static final String CLAIM_EMAIL_VERIFIED = "email_verified";

    @Override
    public Optional<String> id() {
        return token().map(Jwt::getSubject);
    }

    @Override
    public Optional<String> email() {
        return token().map(t -> t.getClaimAsString(CLAIM_EMAIL));
    }

    @Override
    public Optional<String> name() {
        Optional<Jwt> token = token();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        Jwt t = token.get();
        String name = t.getClaimAsString(CLAIM_NAME);
        if (name != null && !name.isBlank()) {
            return Optional.of(name.trim());
        }
        String given = t.getClaimAsString(CLAIM_GIVEN_NAME);
        String family = t.getClaimAsString(CLAIM_FAMILY_NAME);
        String joined = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
        return joined.isBlank() ? Optional.empty() : Optional.of(joined);
    }

    @Override
    public Optional<UUID> orgId() {
        Optional<Jwt> token = token();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        String claim = token.get().getClaimAsString(CLAIM_ORG_ID);
        if (claim == null || claim.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(claim.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    @Override
    public boolean emailVerified() {
        Optional<Jwt> token = token();
        if (token.isEmpty()) {
            return false;
        }
        // Fail-closed: only an explicit boolean `true` (or the string "true") counts as verified.
        // Absent / false / any other value → false. OIDC permits the claim as a JSON boolean; some
        // IdPs stringify it, so accept both shapes but nothing looser.
        Object claim = token.get().getClaim(CLAIM_EMAIL_VERIFIED);
        if (claim instanceof Boolean b) {
            return b;
        }
        if (claim instanceof String s) {
            return "true".equalsIgnoreCase(s.trim());
        }
        return false;
    }

    @Override
    public Set<String> roles() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream().map(Object::toString).collect(Collectors.toSet());
    }

    private Optional<Jwt> token() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.of(jwt.getToken());
        }
        return Optional.empty();
    }
}
