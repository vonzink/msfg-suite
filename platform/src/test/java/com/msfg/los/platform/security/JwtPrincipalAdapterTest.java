package com.msfg.los.platform.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPrincipalAdapterTest {

    private final JwtPrincipalAdapter adapter = new JwtPrincipalAdapter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("t").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    }

    private static void authenticate(Jwt jwt, String... authorities) {
        var auths = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, auths));
    }

    @Test
    void extractsIdEmailNameOrgIdAndRoles() {
        UUID org = UUID.randomUUID();
        Jwt jwt = jwt().subject("sub-123")
                .claim("email", "lo@example.com")
                .claim("name", "Jane Officer")
                .claim("org_id", org.toString())
                .build();
        authenticate(jwt, "ROLE_LO", "ROLE_PROCESSOR");

        assertThat(adapter.id()).contains("sub-123");
        assertThat(adapter.email()).contains("lo@example.com");
        assertThat(adapter.name()).contains("Jane Officer");
        assertThat(adapter.orgId()).contains(org);
        assertThat(adapter.roles()).containsExactlyInAnyOrder("ROLE_LO", "ROLE_PROCESSOR");
    }

    @Test
    void nameFallsBackToGivenPlusFamily_whenNameClaimMissing() {
        Jwt jwt = jwt().subject("s")
                .claim("given_name", "Jane")
                .claim("family_name", "Officer")
                .build();
        authenticate(jwt);

        assertThat(adapter.name()).contains("Jane Officer");
    }

    @Test
    void nameClaimTakesPrecedenceAndIsTrimmed() {
        Jwt jwt = jwt().subject("s")
                .claim("name", "  Full Name  ")
                .claim("given_name", "Jane")
                .claim("family_name", "Officer")
                .build();
        authenticate(jwt);

        assertThat(adapter.name()).contains("Full Name");
    }

    @Test
    void nameUsesGivenOnlyWhenFamilyMissing() {
        Jwt jwt = jwt().subject("s").claim("given_name", "Jane").build();
        authenticate(jwt);

        assertThat(adapter.name()).contains("Jane");
    }

    @Test
    void nameEmptyWhenNoNameClaims() {
        Jwt jwt = jwt().subject("s").build();
        authenticate(jwt);

        assertThat(adapter.name()).isEmpty();
    }

    @Test
    void orgIdEmptyWhenClaimAbsent() {
        Jwt jwt = jwt().subject("s").build();
        authenticate(jwt);

        assertThat(adapter.orgId()).isEmpty();
    }

    @Test
    void orgIdEmptyWhenClaimUnparseable() {
        Jwt jwt = jwt().subject("s").claim("org_id", "not-a-uuid").build();
        authenticate(jwt);

        assertThat(adapter.orgId()).isEmpty();
    }

    @Test
    void allEmptyWhenNoAuthentication() {
        assertThat(adapter.id()).isEmpty();
        assertThat(adapter.email()).isEmpty();
        assertThat(adapter.name()).isEmpty();
        assertThat(adapter.orgId()).isEmpty();
        assertThat(adapter.roles()).isEmpty();
    }

    @Test
    void emailVerifiedTrueOnlyWhenClaimIsBooleanTrue() {
        Jwt jwt = jwt().subject("s").claim("email_verified", true).build();
        authenticate(jwt);
        assertThat(adapter.emailVerified()).isTrue();
    }

    @Test
    void emailVerifiedTrueWhenClaimIsStringTrue() {
        // Some IdPs stringify the claim — accept "true"/"TRUE" but nothing looser.
        Jwt jwt = jwt().subject("s").claim("email_verified", "true").build();
        authenticate(jwt);
        assertThat(adapter.emailVerified()).isTrue();
    }

    @Test
    void emailVerifiedFalseWhenClaimBooleanFalse() {
        Jwt jwt = jwt().subject("s").claim("email_verified", false).build();
        authenticate(jwt);
        assertThat(adapter.emailVerified()).isFalse();
    }

    @Test
    void emailVerifiedFailsClosedWhenClaimAbsent() {
        Jwt jwt = jwt().subject("s").build();
        authenticate(jwt);
        assertThat(adapter.emailVerified()).isFalse();
    }

    @Test
    void emailVerifiedFailsClosedForNonBooleanNonTrueString() {
        Jwt jwt = jwt().subject("s").claim("email_verified", "yes").build();
        authenticate(jwt);
        assertThat(adapter.emailVerified()).isFalse();
    }

    @Test
    void emailVerifiedFailsClosedWhenUnauthenticated() {
        assertThat(adapter.emailVerified()).isFalse();
    }

    @Test
    void claimAccessorsEmptyWhenAuthenticationIsNotJwt() {
        // roles() still derives from authorities (any auth); the claim accessors require a JWT token.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThat(adapter.id()).isEmpty();
        assertThat(adapter.email()).isEmpty();
        assertThat(adapter.name()).isEmpty();
        assertThat(adapter.orgId()).isEmpty();
        assertThat(adapter.emailVerified()).isFalse();
        assertThat(adapter.roles()).containsExactly("ROLE_ADMIN");
    }
}
