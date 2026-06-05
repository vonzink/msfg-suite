package com.msfg.los.tenancy;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrganizationControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    private RequestPostProcessor companyAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void platformAdminCreatesOrg_201() throws Exception {
        mvc.perform(post("/api/admin/organizations").with(platformAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\",\"slug\":\"acme-%s\"}".formatted(System.nanoTime())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.slug").exists());
    }

    @Test
    void companyAdminCannotCreate_403() throws Exception {
        mvc.perform(post("/api/admin/organizations").with(companyAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"slug\":\"x-org-%s\"}".formatted(System.nanoTime())))
            .andExpect(status().isForbidden());
    }

    @Test
    void listRequiresPlatformAdmin() throws Exception {
        mvc.perform(get("/api/admin/organizations").with(companyAdmin()))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/organizations").with(platformAdmin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray());
    }
}
