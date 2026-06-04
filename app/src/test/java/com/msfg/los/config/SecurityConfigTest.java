package com.msfg.los.config;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityConfigTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void noToken_is401() throws Exception {
        mvc.perform(get("/api/loans")).andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealth_isPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void authenticatedPassesSecurity() throws Exception {
        mvc.perform(get("/api/loans").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LO"))))
            .andExpect(status().isOk());
    }

    @Test
    void wrongRoleCannotCreate_403() throws Exception {
        mvc.perform(post("/api/loans").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PROCESSOR"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void loRolePassesCreateAuthz_400WithoutBody() throws Exception {
        // Send an empty JSON object so Spring can parse the body; @NotNull constraints fire → 400
        mvc.perform(post("/api/loans").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LO")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }
}
