package com.msfg.los.tenancy;

import com.msfg.los.platform.tenancy.TenantContextFilter;
import com.msfg.los.platform.tenancy.TenantContextHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private Jwt jwtWithOrg(String orgClaim) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (orgClaim != null) b.claim("org_id", orgClaim);
        return b.build();
    }

    private void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @Test
    void validOrgIdIsBoundDuringChainThenCleared() throws Exception {
        String org = UUID.randomUUID().toString();
        authenticate(jwtWithOrg(org));
        AtomicReference<UUID> seenDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenDuringChain.set(TenantContextHolder.get());
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        assertThat(seenDuringChain.get()).isEqualTo(UUID.fromString(org));
        assertThat(TenantContextHolder.get()).isNull(); // cleared in finally
    }

    @Test
    void malformedOrgIdDoesNotThrowAndNeverBindsTenant() {
        authenticate(jwtWithOrg("not-a-uuid"));
        AtomicReference<UUID> seenDuringChain = new AtomicReference<>(UUID.randomUUID());
        FilterChain chain = (req, res) -> seenDuringChain.set(TenantContextHolder.get());
        assertThatCode(() ->
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .doesNotThrowAnyException();
        assertThat(seenDuringChain.get()).isNull(); // never bound an invalid tenant
    }
}
