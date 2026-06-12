package com.msfg.los.aus.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Org-wide vendor credentials (AUS Task 5): ROLE_ADMIN gate on /api/org/**, replace-only
 * upsert semantics, secrets encrypted at rest, and THE CARDINAL RULE — raw secret values
 * never appear in any response body (passwords: boolean only; usernames: boolean + mask).
 */
class VendorCredentialIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static final String FULL_BODY = """
            {"institutionId":"INST123","username":"fannie-user","password":"s3cret-pw",
             "creditProviderCode":"1","creditUsername":"cred-user","creditPassword":"cred-pw"}""";

    private RequestPostProcessor as(String sub, String role) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private RequestPostProcessor admin() {
        return as(UUID.randomUUID().toString(), "ROLE_ADMIN");
    }

    private String putDu(String body) throws Exception {
        return mvc.perform(put("/api/org/vendor-credentials/DU").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String getList() throws Exception {
        return mvc.perform(get("/api/org/vendor-credentials").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Map<String, Object> duEntry(String listBody) {
        List<Map<String, Object>> dus = JsonPath.read(listBody, "$.data[?(@.vendor=='DU')]");
        assertThat(dus).hasSize(1);
        return dus.get(0);
    }

    @Test
    void adminUpsertsAndReadsMaskedOrgCredential() throws Exception {
        String putBody = putDu(FULL_BODY);
        assertThat((String) JsonPath.read(putBody, "$.data.vendor")).isEqualTo("DU");
        assertThat((String) JsonPath.read(putBody, "$.data.institutionId")).isEqualTo("INST123");
        assertThat((Boolean) JsonPath.read(putBody, "$.data.usernameSet")).isTrue();
        assertThat((String) JsonPath.read(putBody, "$.data.usernameMasked")).isEqualTo("f•••r");
        assertThat((Boolean) JsonPath.read(putBody, "$.data.passwordSet")).isTrue();

        String listBody = getList();
        Map<String, Object> du = duEntry(listBody);
        assertThat(du.get("institutionId")).isEqualTo("INST123");
        assertThat(du.get("usernameSet")).isEqualTo(true);
        assertThat(du.get("usernameMasked")).isEqualTo("f•••r");
        assertThat(du.get("passwordSet")).isEqualTo(true);

        // THE CARDINAL RULE — no raw secret (and no full username) in ANY response body.
        for (String body : List.of(putBody, listBody)) {
            assertThat(body)
                    .doesNotContain("s3cret-pw")
                    .doesNotContain("cred-pw")
                    .doesNotContain("fannie-user");
        }
    }

    @Test
    void replaceOnlySemantics() throws Exception {
        putDu(FULL_BODY);

        // Omitted fields are kept: institutionId overwritten, password untouched.
        putDu("{\"institutionId\":\"INST999\"}");
        Map<String, Object> du = duEntry(getList());
        assertThat(du.get("institutionId")).isEqualTo("INST999");
        assertThat(du.get("passwordSet")).isEqualTo(true);

        // Empty string clears a secret; other secrets stay set.
        putDu("{\"password\":\"\"}");
        du = duEntry(getList());
        assertThat(du.get("passwordSet")).isEqualTo(false);
        assertThat(du.get("usernameSet")).isEqualTo(true);
    }

    @Test
    void encryptedAtRest() throws Exception {
        // Re-PUT inside this test so it is independent of method order.
        putDu("{\"password\":\"s3cret-pw\"}");

        String stored = jdbc.queryForObject(
                "select password from vendor_credential where vendor='DU' and loan_id is null",
                String.class);
        assertThat(stored)
                .isNotEqualTo("s3cret-pw")
                .doesNotContain("s3cret-pw");
    }

    @Test
    void nonAdminForbidden() throws Exception {
        mvc.perform(put("/api/org/vendor-credentials/DU")
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institutionId\":\"X\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/org/vendor-credentials/DU")
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institutionId\":\"X\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/org/vendor-credentials")
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/org/vendor-credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownVendor400() throws Exception {
        mvc.perform(put("/api/org/vendor-credentials/NOPE").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institutionId\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }
}
