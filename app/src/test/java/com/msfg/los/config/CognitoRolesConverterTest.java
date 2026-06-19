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
    void mintsManagerAuthorityButStillDropsUnknownGroup() {
        // MANAGER is a Role enum name → auto-allowlisted (no converter change). A bogus group is dropped.
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u")
            .claim("cognito:groups", List.of("MANAGER", "SUPERMANAGER"))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
            .containsExactly(Role.MANAGER.authority());
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
    void mapsRealCognitoPoolGroupStringsToSuiteRoles() {
        // The shared Cognito pool (inherited from mortgage-app) emits these exact group strings.
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u")
            .claim("cognito:groups", List.of("Admin", "Manager", "LO", "Processor", "Borrower", "RealEstateAgent"))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder(
                "ROLE_ADMIN", "ROLE_MANAGER", "ROLE_LO", "ROLE_PROCESSOR",
                "ROLE_BORROWER", "ROLE_REAL_ESTATE_AGENT");
    }

    @Test
    void mapsBorrowerAndRealEstateAgentPoolGroupsToNewRoles() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u")
            .claim("cognito:groups", List.of("Borrower", "RealEstateAgent"))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder(Role.BORROWER.authority(), Role.REAL_ESTATE_AGENT.authority());
    }

    @Test
    void dropsDormantExternalPoolGroupWithNoSuiteEquivalent() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u")
            .claim("cognito:groups", List.of("LO", "External"))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_LO");
    }

    @Test
    void dropsMisCasedAndJunkVariants_failClosed() {
        // Matching is case-sensitive: only the exact pool strings / enum names resolve.
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u")
            .claim("cognito:groups", List.of("borrower", "realestateagent", "REALESTATEAGENT",
                "admin", "GARBAGE", "Lo"))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void newRolesYieldExpectedAuthorityStrings() {
        assertThat(Role.BORROWER.authority()).isEqualTo("ROLE_BORROWER");
        assertThat(Role.REAL_ESTATE_AGENT.authority()).isEqualTo("ROLE_REAL_ESTATE_AGENT");
    }

    @Test
    void emptyWhenNoGroups() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(jwt)).isEmpty();
    }
}
