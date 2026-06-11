package com.msfg.los.coc;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CocDecisionIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    // Same sub as LO (same person, same org), different role — mirrors LoanControllerIT pattern
    private RequestPostProcessor underwriter() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String submitEntry(String loanId) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/coc/submit", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"BORROWER_REQUESTED\"}"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- LO → 403 (no UNDERWRITER role) ---

    @Test
    void loRoleDecisionReturns403() throws Exception {
        String loanId = createLoan();
        String entryId = submitEntry(loanId);

        mvc.perform(post("/api/loans/{l}/coc/history/{e}/decision", loanId, entryId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isForbidden());
    }

    // --- UNDERWRITER → 200 ACCEPTED with decisionBy + decisionDate ---

    @Test
    void underwriterDecisionAcceptReturns200Accepted() throws Exception {
        String loanId = createLoan();
        String entryId = submitEntry(loanId);

        mvc.perform(post("/api/loans/{l}/coc/history/{e}/decision", loanId, entryId).with(underwriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.decisionBy").value(notNullValue()))
                .andExpect(jsonPath("$.data.decisionDate").value(notNullValue()));
    }

    // --- 2nd decision on same entry (underwriter) → 409 ---

    @Test
    void secondDecisionReturns409() throws Exception {
        String loanId = createLoan();
        String entryId = submitEntry(loanId);

        mvc.perform(post("/api/loans/{l}/coc/history/{e}/decision", loanId, entryId).with(underwriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/loans/{l}/coc/history/{e}/decision", loanId, entryId).with(underwriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DENY\"}"))
                .andExpect(status().isConflict());
    }

    // --- random entryId → 404 ---

    @Test
    void randomEntryIdReturns404() throws Exception {
        String loanId = createLoan();
        String randomEntryId = UUID.randomUUID().toString();

        mvc.perform(post("/api/loans/{l}/coc/history/{e}/decision", loanId, randomEntryId).with(underwriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isNotFound());
    }

    // --- cross-org → 404 ---

    @Test
    void crossOrgReturns404() throws Exception {
        String loanId = createLoan();
        String entryId = submitEntry(loanId);

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"));

        mvc.perform(post("/api/loans/{l}/coc/history/{e}/decision", loanId, entryId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isNotFound());
    }

    // --- invalid enum constant in body → 400 VALIDATION_ERROR, not a 500 ---

    @Test
    void invalidDecisionEnumReturns400ValidationError() throws Exception {
        String loanId = createLoan();
        String entryId = submitEntry(loanId);

        mvc.perform(post("/api/loans/{l}/coc/history/{e}/decision", loanId, entryId).with(underwriter())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // --- no token → 401 ---

    @Test
    void noTokenReturns401() throws Exception {
        mvc.perform(post("/api/loans/{l}/coc/history/{e}/decision",
                        UUID.randomUUID(), UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isUnauthorized());
    }
}
