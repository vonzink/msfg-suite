package com.msfg.los.config;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for CORS policy — runs under the real SecurityConfig chain (test profile).
 *
 * <p>Key invariants verified:
 * <ul>
 *   <li>Allowed origins receive the ACAO header and a 200 preflight response.</li>
 *   <li>Disallowed origins do NOT receive the ACAO header and get a 403.</li>
 *   <li>No wildcard is ever emitted in the ACAO header.</li>
 *   <li>The production origins (app.msfgco.com, los.msfgco.com) are echoed when configured.</li>
 *   <li>Preflight is checked without a JWT — the CORS filter runs before auth.</li>
 * </ul>
 */
@TestPropertySource(properties = "los.cors.allowed-origins=https://app.example.test")
class CorsIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void preflightFromAllowedOriginIsPermitted() throws Exception {
        mvc.perform(options("/api/loans")
                .header("Origin", "https://app.example.test")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.test"));
    }

    @Test
    void preflightFromDisallowedOriginIsRejected() throws Exception {
        mvc.perform(options("/api/loans")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void wildcardIsNeverEmittedForAllowedOrigin() throws Exception {
        mvc.perform(options("/api/loans")
                .header("Origin", "https://app.example.test")
                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                // Must echo the exact origin, not a wildcard.
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.test"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}

/**
 * CORS assertions for the production-domain origins (app.msfgco.com, los.msfgco.com).
 * Uses a separate inner class so the {@code @TestPropertySource} allowlist is independent.
 */
@TestPropertySource(properties = "los.cors.allowed-origins=https://app.msfgco.com,https://los.msfgco.com")
class CorsProductionOriginsIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void preflightFromAppMsfgcoIsPermitted() throws Exception {
        mvc.perform(options("/api/loans")
                .header("Origin", "https://app.msfgco.com")
                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.msfgco.com"));
    }

    @Test
    void preflightFromLosMsfgcoIsPermitted() throws Exception {
        mvc.perform(options("/api/loans")
                .header("Origin", "https://los.msfgco.com")
                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://los.msfgco.com"));
    }

    @Test
    void preflightFromUnlistedOriginIsRejected() throws Exception {
        mvc.perform(options("/api/loans")
                .header("Origin", "https://attacker.msfgco.com.evil.example")
                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void wildcardIsNeverEmittedForProductionOrigin() throws Exception {
        mvc.perform(options("/api/loans")
                .header("Origin", "https://app.msfgco.com")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.msfgco.com"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}

/**
 * CORS assertions for the local funnel-walk origin (mortgage-app React FE on :3001)
 * and the dev-identity headers used in local mode.
 *
 * <p>The :3001 origin is only configured in the {@code local} profile
 * ({@code application-local.yml}), so we override it explicitly here. The dev-header
 * assertion is profile-independent (CorsConfig is global).
 */
@TestPropertySource(properties = "los.cors.allowed-origins=http://localhost:3001")
class CorsLocalFunnelIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void preflightFromLocalhost3001IsPermitted() throws Exception {
        mvc.perform(options("/api/loans")
                .header("Origin", "http://localhost:3001")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3001"));
    }

    @Test
    void preflightWithDevSubHeaderIsPermitted() throws Exception {
        mvc.perform(options("/api/loans")
                .header("Origin", "http://localhost:3001")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "X-Dev-Sub"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3001"))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        containsStringIgnoringCase("X-Dev-Sub")));
    }

    @Test
    void preflightWithDevRolesHeaderIsPermitted() throws Exception {
        mvc.perform(options("/api/loans")
                .header("Origin", "http://localhost:3001")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "X-Dev-Roles"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers",
                        containsStringIgnoringCase("X-Dev-Roles")));
    }

    @Test
    void preflightWithDevOrgHeaderIsPermitted() throws Exception {
        mvc.perform(options("/api/loans")
                .header("Origin", "http://localhost:3001")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "X-Dev-Org"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers",
                        containsStringIgnoringCase("X-Dev-Org")));
    }
}
