package com.msfg.los.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CognitoRolesConverterTest {

    private final CognitoRolesConverter converter = new CognitoRolesConverter();

    @Test
    void mapsGroupsToRoleAuthorities() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u")
            .claim("cognito:groups", List.of("LO", "ADMIN"))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        Collection<GrantedAuthority> auths = converter.convert(jwt);
        assertThat(auths).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_LO", "ROLE_ADMIN");
    }

    @Test
    void emptyWhenNoGroups() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(jwt)).isEmpty();
    }
}
