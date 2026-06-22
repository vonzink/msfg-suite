package com.msfg.los.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static com.msfg.los.config.LocalDevSecurityConfig.DEV_ORG_ID;
import static com.msfg.los.config.LocalDevSecurityConfig.DEV_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DevPrincipalFilter's dev-header identity bridge.
 * No Testcontainers, no Spring context — pure filter unit test.
 */
class LocalDevHeaderBridgeTest {

    private final LocalDevSecurityConfig.DevPrincipalFilter filter =
            new LocalDevSecurityConfig.DevPrincipalFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noHeaders_defaultsToDevAdmin() throws Exception {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getToken().getSubject()).isEqualTo(DEV_USER_ID);
        assertThat(auth.getToken().getClaimAsString("org_id")).isEqualTo(DEV_ORG_ID);
        assertThat(auth.getAuthorities())
                .contains(new SimpleGrantedAuthority("ROLE_ADMIN"))
                .contains(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    @Test
    void borrowerHeaders_producesBorrowerPrincipal() throws Exception {
        String borrowerSub = "00000000-0000-0000-0000-0000000000b0";
        String borrowerOrg = "00000000-0000-0000-0000-0000000000aa";

        var req = new MockHttpServletRequest();
        req.addHeader("X-Dev-Sub", borrowerSub);
        req.addHeader("X-Dev-Roles", "Borrower");
        req.addHeader("X-Dev-Org", borrowerOrg);
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getToken().getSubject()).isEqualTo(borrowerSub);
        assertThat(auth.getToken().getClaimAsString("org_id")).isEqualTo(borrowerOrg);
        assertThat(auth.getToken().getClaimAsStringList("cognito:groups")).containsExactly("Borrower");
        assertThat(auth.getAuthorities())
                .contains(new SimpleGrantedAuthority("ROLE_BORROWER"))
                .doesNotContain(new SimpleGrantedAuthority("ROLE_ADMIN"))
                .doesNotContain(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    @Test
    void multipleRoles_producesAllAuthorities() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("X-Dev-Roles", "LO,Processor");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities())
                .contains(new SimpleGrantedAuthority("ROLE_LO"))
                .contains(new SimpleGrantedAuthority("ROLE_PROCESSOR"));
    }
}
