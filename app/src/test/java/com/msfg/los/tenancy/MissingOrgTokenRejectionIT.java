package com.msfg.los.tenancy;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A decoded JWT with no usable org_id must fail closed at the converter and render the ApiError
 *  envelope through the real resource-server chain (proves Task 1 + Task 2 end-to-end). */
@Import(MissingOrgTokenRejectionIT.NoOrgDecoder.class)
class MissingOrgTokenRejectionIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void tokenWithoutOrgIdIsRejectedWithEnvelopedUnauthorized() throws Exception {
        mvc.perform(get("/api/loans").header("Authorization", "Bearer any-token-value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @TestConfiguration
    static class NoOrgDecoder {
        @Bean
        @Primary
        JwtDecoder noOrgJwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").subject("u")
                    .claim("cognito:groups", List.of("LO"))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                    .build(); // NOTE: deliberately no org_id claim
        }
    }
}
