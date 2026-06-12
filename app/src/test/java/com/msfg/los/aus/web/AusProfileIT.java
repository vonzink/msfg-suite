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

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-loan AUS profile (AUS Task 7): 1:1-per-loan GET-empty/PUT-upsert of DU + LPA submission
 * settings (issue mode, credit provider, FHA case number, per-borrower credit references) plus a
 * per-vendor {@code credentialSource} (NONE/ORG/LOAN) resolved through VendorCredentialService.
 */
class AusProfileIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

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

    /** Creates a loan owned by the given LO subject; returns the loan id. */
    private String createLoan(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Adds a borrower to the loan via the real parties endpoint; returns the borrower id. */
    private String createBorrower(String loSub, String loanId) throws Exception {
        var res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"primary\":true}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /**
     * Tests share one DB; other classes (VendorCredentialIT) seed org-level DU creds for
     * DEFAULT_ORG. Tests asserting credentialSource start from a clean slate.
     */
    private void deleteOrgCredentials() {
        jdbc.update("delete from vendor_credential where loan_id is null and org_id = ?::uuid", DEFAULT_ORG);
    }

    @Test
    void getBeforeAnySaveReturnsEmpty200() throws Exception {
        deleteOrgCredentials();
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(get("/api/loans/{loanId}/aus/profile", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.du.issueMode").value(nullValue()))
                .andExpect(jsonPath("$.data.du.creditReferences").isArray())
                .andExpect(jsonPath("$.data.du.creditReferences").isEmpty())
                .andExpect(jsonPath("$.data.du.credentialSource").value("NONE"))
                .andExpect(jsonPath("$.data.lpa.creditReferences").isArray())
                .andExpect(jsonPath("$.data.lpa.creditReferences").isEmpty())
                .andExpect(jsonPath("$.data.lpa.credentialSource").value("NONE"));
    }

    @Test
    void putRoundTripsSettingsAndRefs() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String borrowerId = createBorrower(lo, loanId);

        mvc.perform(put("/api/loans/{loanId}/aus/profile", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"du":{"issueMode":"REISSUE","creditProviderCode":"1","fhaCaseNumber":"011-1234567",
                                 "creditReferences":[{"borrowerId":"%s","reference":"ABC123"}]}}""".formatted(borrowerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.du.issueMode").value("REISSUE"));

        // FRESH GET — the persisted state, not the PUT echo.
        mvc.perform(get("/api/loans/{loanId}/aus/profile", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.du.issueMode").value("REISSUE"))
                .andExpect(jsonPath("$.data.du.creditProviderCode").value("1"))
                .andExpect(jsonPath("$.data.du.fhaCaseNumber").value("011-1234567"))
                .andExpect(jsonPath("$.data.du.branchNumber").value(nullValue()))
                .andExpect(jsonPath("$.data.du.creditReferences.length()").value(1))
                .andExpect(jsonPath("$.data.du.creditReferences[0].borrowerId").value(borrowerId))
                .andExpect(jsonPath("$.data.du.creditReferences[0].reference").value("ABC123"))
                // LPA untouched: null settings, empty refs.
                .andExpect(jsonPath("$.data.lpa.issueMode").value(nullValue()))
                .andExpect(jsonPath("$.data.lpa.creditReferences").isArray())
                .andExpect(jsonPath("$.data.lpa.creditReferences").isEmpty());
    }

    @Test
    void partialPutLeavesOtherVendorAlone() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String borrowerId = createBorrower(lo, loanId);

        mvc.perform(put("/api/loans/{loanId}/aus/profile", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"du":{"issueMode":"REISSUE","creditProviderCode":"1","fhaCaseNumber":"011-1234567",
                                 "creditReferences":[{"borrowerId":"%s","reference":"ABC123"}]}}""".formatted(borrowerId)))
                .andExpect(status().isOk());

        // Null du in the body = leave DU alone; only LPA changes.
        mvc.perform(put("/api/loans/{loanId}/aus/profile", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lpa\":{\"issueMode\":\"ORDER\",\"branchNumber\":\"BR-9\"}}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/loans/{loanId}/aus/profile", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lpa.issueMode").value("ORDER"))
                .andExpect(jsonPath("$.data.lpa.branchNumber").value("BR-9"))
                .andExpect(jsonPath("$.data.lpa.creditReferences").isArray())
                .andExpect(jsonPath("$.data.lpa.creditReferences").isEmpty())
                // DU still carries its REISSUE settings.
                .andExpect(jsonPath("$.data.du.issueMode").value("REISSUE"))
                .andExpect(jsonPath("$.data.du.fhaCaseNumber").value("011-1234567"))
                .andExpect(jsonPath("$.data.du.creditReferences.length()").value(1));
    }

    @Test
    void unknownBorrowerRefRejected400() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(put("/api/loans/{loanId}/aus/profile", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"du":{"issueMode":"REISSUE",
                                 "creditReferences":[{"borrowerId":"%s","reference":"X1"}]}}"""
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("borrower")));
    }

    @Test
    void credentialSourceReflectsOrgCreds() throws Exception {
        deleteOrgCredentials();
        mvc.perform(put("/api/org/vendor-credentials/DU").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institutionId\":\"X\"}"))
                .andExpect(status().isOk());

        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(get("/api/loans/{loanId}/aus/profile", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.du.credentialSource").value("ORG"))
                .andExpect(jsonPath("$.data.lpa.credentialSource").value("NONE"));
    }

    @Test
    void crossOrgGet404() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(get("/api/loans/{loanId}/aus/profile", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff")))
                .andExpect(status().isNotFound());
    }

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{loanId}/aus/profile", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
