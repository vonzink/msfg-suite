package com.msfg.los.income.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IncomeVerificationIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- happy path: VOI ---

    @Test
    void orderVoiReturns201WithStubResult() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{loanId}/income/verifications", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationType\":\"VOI\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ORDERED"))
                .andExpect(jsonPath("$.data.provider").value("STUB"))
                .andExpect(jsonPath("$.data.referenceNumber", startsWith("STUB-")));
    }

    // --- list after one order ---

    @Test
    void listAfterOneOrderReturnsLengthOne() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{loanId}/income/verifications", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationType\":\"VOI\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/loans/{loanId}/income/verifications", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // --- second order (TAX_TRANSCRIPT) → list length 2 ---

    @Test
    void twoOrdersListLengthTwo() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{loanId}/income/verifications", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationType\":\"VOI\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/loans/{loanId}/income/verifications", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationType\":\"TAX_TRANSCRIPT\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/loans/{loanId}/income/verifications", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // --- cross-org JWT → 404 ---

    @Test
    void crossOrgPostReturns404() throws Exception {
        String loanId = createLoan();

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(post("/api/loans/{loanId}/income/verifications", loanId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationType\":\"VOI\"}"))
                .andExpect(status().isNotFound());
    }

    // --- cross-loan borrower → 400 ---

    @Test
    void orderWithForeignBorrowerReturns400() throws Exception {
        // L1 + B1
        String l1 = createLoan();
        String b1 = addBorrower(l1);

        // L2 + B2 (different loan, same org)
        String l2 = createLoan();
        String b2 = addBorrower(l2);

        // POST to L1 with B2's id → borrower does not belong to L1 → 400
        mvc.perform(post("/api/loans/{loanId}/income/verifications", l1).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationType\":\"VOI\",\"borrowerId\":\"%s\"}".formatted(b2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("borrowerId")));

        // Sanity: same-loan borrower is still accepted → 201
        mvc.perform(post("/api/loans/{loanId}/income/verifications", l1).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationType\":\"VOI\",\"borrowerId\":\"%s\"}".formatted(b1)))
                .andExpect(status().isCreated());
    }

    // --- no token → 401 ---

    @Test
    void noTokenReturns401() throws Exception {
        mvc.perform(post("/api/loans/{loanId}/income/verifications", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationType\":\"VOI\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private String addBorrower(String loanId) throws Exception {
        var res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"primary\":true}"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }
}
