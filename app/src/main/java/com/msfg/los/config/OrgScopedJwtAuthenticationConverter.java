package com.msfg.los.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.UUID;

/**
 * Authenticates a Cognito JWT only if it carries a parseable {@code org_id} UUID claim, failing
 * closed otherwise. This is the single chokepoint that prevents a tenant-less token from ever
 * authenticating — which would otherwise let {@code OrgTenantResolver} fall back to NIL and stamp
 * {@code org_id = 00000000-0000-0000-0000-000000000000} on writes. Authority mapping is delegated
 * to {@link CognitoRolesConverter} (unchanged).
 */
public class OrgScopedJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter delegate;

    public OrgScopedJwtAuthenticationConverter() {
        this.delegate = new JwtAuthenticationConverter();
        this.delegate.setJwtGrantedAuthoritiesConverter(new CognitoRolesConverter());
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Object claim = jwt.getClaim("org_id");
        if (claim == null || claim.toString().isBlank()) {
            throw new InvalidBearerTokenException("missing org_id claim");
        }
        try {
            UUID.fromString(claim.toString().trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidBearerTokenException("org_id claim is not a valid UUID");
        }
        return delegate.convert(jwt);
    }
}
