package com.msfg.los.config;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
}
