package com.msfg.los.financials.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LiabilityControllerIT extends AbstractIntegrationTest {

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

    private String addBorrower(String loanId) throws Exception {
        var res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"primary\":true}"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addLiability(String loanId, String borrowerId, String body) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- add INSTALLMENT with monthlyPayment → 201 ---

    @Test
    void addInstallmentWithMonthlyPaymentReturns201() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liabilityType\":\"INSTALLMENT\",\"monthlyPayment\":350,\"creditorName\":\"AutoLender\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.liabilityType").value("INSTALLMENT"))
                .andExpect(jsonPath("$.data.monthlyPayment").value(350))
                .andExpect(jsonPath("$.data.includeInDti").value(true))
                .andExpect(jsonPath("$.data.ordinal").value(0));
    }

    // --- exclude without reason → 400, $.message ~ "exclusionReason" ---

    @Test
    void excludeWithoutReasonReturns400() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liabilityType\":\"REVOLVING\",\"includeInDti\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("exclusionReason")));
    }

    // --- exclude WITH reason → 201 ---

    @Test
    void excludeWithReasonReturns201() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liabilityType\":\"INSTALLMENT\",\"includeInDti\":false,\"exclusionReason\":\"PAID_AT_OR_BEFORE_CLOSING\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.includeInDti").value(false))
                .andExpect(jsonPath("$.data.exclusionReason").value("PAID_AT_OR_BEFORE_CLOSING"));
    }

    // --- add includeInDti=true with a reason → 201, reason cleared ---

    @Test
    void addIncludedWithReasonClearsReason() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liabilityType\":\"REVOLVING\",\"includeInDti\":true,\"exclusionReason\":\"OTHER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.includeInDti").value(true))
                .andExpect(jsonPath("$.data.exclusionReason").doesNotExist());
    }

    // --- PATCH excluded→included recovers (exclusionReason cleared) ---

    @Test
    void patchExcludedToIncludedClearsExclusionReason() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        // create excluded liability
        String liabilityId = addLiability(loanId, borrowerId,
                "{\"liabilityType\":\"INSTALLMENT\",\"includeInDti\":false,\"exclusionReason\":\"PAID_AT_OR_BEFORE_CLOSING\"}");

        // PATCH: set includeInDti=true only → should clear exclusionReason (recoverable)
        mvc.perform(patch("/api/loans/{l}/borrowers/{b}/liabilities/{lid}", loanId, borrowerId, liabilityId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includeInDti\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.includeInDti").value(true))
                .andExpect(jsonPath("$.data.exclusionReason").doesNotExist());
    }

    // --- negative monthlyPayment → 400, $.message ~ "monthlyPayment" ---

    @Test
    void negativeMonthlyPaymentReturns400() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liabilityType\":\"INSTALLMENT\",\"monthlyPayment\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("monthlyPayment")));
    }

    // --- PATCH negative monthlyPayment on existing liability → 400 (effective state) ---

    @Test
    void patchNegativeMonthlyPaymentOnExistingReturns400() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        String liabilityId = addLiability(loanId, borrowerId,
                "{\"liabilityType\":\"INSTALLMENT\",\"monthlyPayment\":500}");

        mvc.perform(patch("/api/loans/{l}/borrowers/{b}/liabilities/{lid}", loanId, borrowerId, liabilityId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyPayment\":-50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("monthlyPayment")));
    }

    // --- 2nd liability gets ordinal=1 ---

    @Test
    void secondLiabilityGetsOrdinalOne() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        addLiability(loanId, borrowerId, "{\"liabilityType\":\"REVOLVING\"}");

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liabilityType\":\"INSTALLMENT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ordinal").value(1));

        mvc.perform(get("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].ordinal").value(1));
    }

    // --- DELETE → 204 ---

    @Test
    void deleteLiabilityReturns204() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        String liabilityId = addLiability(loanId, borrowerId, "{\"liabilityType\":\"INSTALLMENT\"}");

        mvc.perform(delete("/api/loans/{l}/borrowers/{b}/liabilities/{lid}", loanId, borrowerId, liabilityId).with(lo()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- cross-org → 404 ---

    @Test
    void otherOrgCannotAddLiabilityReturns404() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liabilityType\":\"REVOLVING\"}"))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/liabilities", UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
