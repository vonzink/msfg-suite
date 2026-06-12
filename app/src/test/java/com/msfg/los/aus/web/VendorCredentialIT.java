package com.msfg.los.aus.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.aus.domain.CredentialSource;
import com.msfg.los.aus.domain.CredentialVendor;
import com.msfg.los.aus.service.ResolvedCredentials;
import com.msfg.los.aus.service.VendorCredentialService;
import com.msfg.los.platform.tenancy.TenantContextHolder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Org-wide vendor credentials (AUS Task 5): ROLE_ADMIN gate on /api/org/**, replace-only
 * upsert semantics, secrets encrypted at rest, and THE CARDINAL RULE — raw secret values
 * never appear in any response body (passwords: boolean only; usernames: boolean + mask).
 *
 * <p>Per-loan overrides + whole-row resolution (AUS Task 6): loan row wins outright over the
 * org row (no per-field merging), else org row, else NONE.
 */
class VendorCredentialIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    VendorCredentialService credentialService;

    private static final String FULL_BODY = """
            {"institutionId":"INST123","username":"fannie-user","password":"s3cret-pw",
             "creditProviderCode":"1","creditUsername":"cred-user","creditPassword":"cred-pw"}""";

    private RequestPostProcessor as(String sub, String role) {
        return as(sub, role, DEFAULT_ORG);
    }

    private RequestPostProcessor as(String sub, String role, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org))
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

    // ------------------------------------------------------------------
    // Task 6 — per-loan overrides + whole-row resolution
    // ------------------------------------------------------------------

    /** Creates a loan owned by the given LO subject; returns the loan id. */
    private String createLoan(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Service-level resolve with the tenant set the way TenantContextFilter would (clear in finally). */
    private ResolvedCredentials resolve(String loanId, CredentialVendor vendor) {
        TenantContextHolder.set(UUID.fromString(DEFAULT_ORG));
        try {
            return credentialService.resolve(UUID.fromString(loanId), vendor);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void loanOverrideUpsertReadDelete() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        String putBody = mvc.perform(put("/api/loans/{loanId}/aus/credentials/DU", loanId)
                        .with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"institutionId":"LOAN-INST","username":"loan-user-1","password":"loan-pass-value-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.institutionId").value("LOAN-INST"))
                .andExpect(jsonPath("$.data.usernameSet").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String listBody = mvc.perform(get("/api/loans/{loanId}/aus/credentials", loanId)
                        .with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> dus = JsonPath.read(listBody, "$.data[?(@.vendor=='DU')]");
        assertThat(dus).hasSize(1);
        assertThat(dus.get(0).get("institutionId")).isEqualTo("LOAN-INST");

        // THE CARDINAL RULE — no raw secret (and no full username) in ANY response body.
        for (String body : List.of(putBody, listBody)) {
            assertThat(body)
                    .doesNotContain("loan-pass-value-1")
                    .doesNotContain("loan-user-1");
        }

        mvc.perform(delete("/api/loans/{loanId}/aus/credentials/DU", loanId)
                        .with(as(lo, "ROLE_LO")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/loans/{loanId}/aus/credentials", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void loanCredentialsCrossOrg404() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(put("/api/loans/{loanId}/aus/credentials/DU", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institutionId\":\"X\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolutionPrecedence() throws Exception {
        // Seed via HTTP: org-level DU creds (admin) + a loan (LO).
        mvc.perform(put("/api/org/vendor-credentials/DU").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"org-user-1","password":"org-pass-value-1","sellerServicerNumber":"SS-ORG"}"""))
                .andExpect(status().isOk());
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        // (a) No override -> the org row, whole.
        ResolvedCredentials r = resolve(loanId, CredentialVendor.DU);
        assertThat(r.source()).isEqualTo(CredentialSource.ORG);
        assertThat(r.username()).isEqualTo("org-user-1");
        assertThat(r.sellerServicerNumber()).isEqualTo("SS-ORG");

        // (b) Loan override -> the loan row WHOLE; org fields must NOT bleed through.
        mvc.perform(put("/api/loans/{loanId}/aus/credentials/DU", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"loan-user-1","institutionId":"LOAN-INST"}"""))
                .andExpect(status().isOk());
        r = resolve(loanId, CredentialVendor.DU);
        assertThat(r.source()).isEqualTo(CredentialSource.LOAN);
        assertThat(r.username()).isEqualTo("loan-user-1");
        assertThat(r.institutionId()).isEqualTo("LOAN-INST");
        assertThat(r.sellerServicerNumber()).isNull(); // whole-row precedence, no per-field merge

        // (c) Delete the override -> back to ORG.
        mvc.perform(delete("/api/loans/{loanId}/aus/credentials/DU", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isNoContent());
        r = resolve(loanId, CredentialVendor.DU);
        assertThat(r.source()).isEqualTo(CredentialSource.ORG);
        assertThat(r.username()).isEqualTo("org-user-1");

        // (d) No rows at all (LPA) -> NONE.
        r = resolve(loanId, CredentialVendor.LPA);
        assertThat(r.source()).isEqualTo(CredentialSource.NONE);
        assertThat(r.username()).isNull();
    }
}
