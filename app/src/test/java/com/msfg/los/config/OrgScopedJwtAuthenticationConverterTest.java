package com.msfg.los.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrgScopedJwtAuthenticationConverterTest {

    private final OrgScopedJwtAuthenticationConverter converter = new OrgScopedJwtAuthenticationConverter();

    private Jwt.Builder base() {
        return Jwt.withTokenValue("t").header("alg", "none").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void rejectsWhenOrgIdMissing() {
        Jwt jwt = base().claim("cognito:groups", List.of("LO")).build();
        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsWhenOrgIdBlank() {
        Jwt jwt = base().claim("org_id", "   ").claim("cognito:groups", List.of("LO")).build();
        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void rejectsWhenOrgIdNotUuid() {
        Jwt jwt = base().claim("org_id", "not-a-uuid").claim("cognito:groups", List.of("LO")).build();
        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void authenticatesAndMapsRolesWhenOrgIdValid() {
        String org = UUID.randomUUID().toString();
        Jwt jwt = base().claim("org_id", org).claim("cognito:groups", List.of("LO", "ADMIN")).build();
        AbstractAuthenticationToken token = converter.convert(jwt);
        assertThat(token.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_LO", "ROLE_ADMIN");
    }
}
