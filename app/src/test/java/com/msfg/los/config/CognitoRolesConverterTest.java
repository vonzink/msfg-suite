package com.msfg.los.config;

import com.msfg.los.platform.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Arrays;
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
    void dropsGroupsNotInRoleEnum_caseSensitive() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u")
            .claim("cognito:groups", List.of("LO", "NOT_A_ROLE", "admin", "PROCESSOR"))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        Collection<GrantedAuthority> auths = converter.convert(jwt);
        assertThat(auths).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_LO", "ROLE_PROCESSOR");
    }

    @Test
    void allRoleEnumNamesStillConvert() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u")
            .claim("cognito:groups", Arrays.stream(Role.values()).map(Enum::name).toList())
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder(
                Arrays.stream(Role.values()).map(Role::authority).toArray(String[]::new));
    }

    @Test
    void emptyWhenNoGroups() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(jwt)).isEmpty();
    }
}
