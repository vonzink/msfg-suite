package com.msfg.los.openapi;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression guard: ensures GET /v3/api-docs returns HTTP 200 and parseable OpenAPI JSON.
 *
 * A 500 here is typically caused by a springdoc schema-name collision (two classes sharing
 * the same simple name but different packages, when springdoc.use-fqn=false, the default).
 */
class OpenApiDocsIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void openApiDocs_returns200WithValidJson() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/loans']").exists());
    }
}
