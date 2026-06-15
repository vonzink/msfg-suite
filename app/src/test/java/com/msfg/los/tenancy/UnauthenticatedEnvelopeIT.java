package com.msfg.los.tenancy;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A request to a protected /api/** endpoint with NO bearer token must fail closed (401)
 *  and render through the standard ApiError envelope, not Spring's default 401 body. */
class UnauthenticatedEnvelopeIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void noTokenOnProtectedApiReturnsEnvelopedUnauthorized() throws Exception {
        mvc.perform(get("/api/loans"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
