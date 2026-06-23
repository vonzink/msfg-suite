package com.msfg.los.identity.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LO/Admin user administration — create user + reset password via the {@code UserAdminPort}
 * (Cognito write-side seam; stub adapter under the {@code test} profile).
 */
class UserAdminControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority(role));
    }

    @Test
    void adminCreatesBorrowerUser() throws Exception {
        String email = "newbuyer+" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/admin/users").with(as("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"name\":\"New Buyer\",\"role\":\"BORROWER\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.email").value(email));

        Integer rows = jdbc.queryForObject(
                "select count(*) from user_account where org_id = ?::uuid and email = ? and role = 'BORROWER'",
                Integer.class, DEFAULT_ORG, email);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void loCannotCreateStaffUser() throws Exception {
        // Privilege-escalation guard: an LO reaches the endpoint (filter allows LO+ADMIN) but must NOT
        // be able to mint a staff/admin user — only borrowers/agents. No row may be created.
        String email = "sneaky+" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/admin/users").with(as("ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"name\":\"Sneaky\",\"role\":\"ADMIN\"}".formatted(email)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Integer rows = jdbc.queryForObject(
                "select count(*) from user_account where email = ?", Integer.class, email);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void loCanCreateBorrowerUser() throws Exception {
        // The guard must not over-block: an LO creating a BORROWER is allowed.
        String email = "buyer+" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/admin/users").with(as("ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"name\":\"Buyer\",\"role\":\"BORROWER\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("BORROWER"));
    }

    /** Creates a user as the given actor role + payload role, returning the new user's id. */
    private String createUserAs(String actorRole, String userRole) throws Exception {
        String email = "u+" + UUID.randomUUID() + "@example.com";
        String body = mvc.perform(post("/api/admin/users").with(as(actorRole))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"name\":\"U\",\"role\":\"%s\"}".formatted(email, userRole)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.data.id");
    }

    @Test
    void adminCanResetExistingUserPassword() throws Exception {
        String id = createUserAs("ROLE_ADMIN", "BORROWER");
        mvc.perform(post("/api/admin/users/{id}/reset-password", id).with(as("ROLE_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void resetUnknownUserIs404() throws Exception {
        mvc.perform(post("/api/admin/users/{id}/reset-password", UUID.randomUUID()).with(as("ROLE_ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void loCannotResetStaffUserPassword() throws Exception {
        // Escalation guard on reset: an LO must not reset a staff/admin user's password.
        String adminUserId = createUserAs("ROLE_ADMIN", "ADMIN");
        mvc.perform(post("/api/admin/users/{id}/reset-password", adminUserId).with(as("ROLE_LO")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void blankEmailIs400() throws Exception {
        mvc.perform(post("/api/admin/users").with(as("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  \",\"name\":\"X\",\"role\":\"BORROWER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unknownRoleIs400() throws Exception {
        String email = "x+" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/admin/users").with(as("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"name\":\"X\",\"role\":\"WIZARD\"}".formatted(email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void duplicateEmailIs409() throws Exception {
        String email = "dup+" + UUID.randomUUID() + "@example.com";
        String payload = "{\"email\":\"%s\",\"name\":\"Dup\",\"role\":\"BORROWER\"}".formatted(email);
        mvc.perform(post("/api/admin/users").with(as("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/admin/users").with(as("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }
}
