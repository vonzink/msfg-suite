package com.msfg.los.pricing.web;

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

class PricingControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG).claim("email", "lo@msfg.test"))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    void patchLoan(String loanId, String jsonBody) throws Exception {
        mvc.perform(patch("/api/loans/{id}", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk());
    }

    /** Standard priceable loan: total 300000, ltv 80.000 (basis = min(375k sales, 380k appraised)), fico 745. */
    String priceableLoan() throws Exception {
        String id = createLoan();
        patchLoan(id, """
                {
                  "baseLoanAmount": 300000,
                  "interestRate": 6.5,
                  "loanTermMonths": 360,
                  "qualifyingCreditScore": 745,
                  "salesPrice": 375000,
                  "appraisedValue": 380000,
                  "amortizationType": "FIXED"
                }
                """);
        return id;
    }

    // ── Virgin reads ──────────────────────────────────────────────────────────

    @Test
    void getPricing_onUnlockedLoan_returnsNotLockedWithLoanRatePassthrough() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(get("/api/loans/{id}/pricing", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockStatus").value("NOT_LOCKED"))
                .andExpect(jsonPath("$.data.interestRate").value(6.5))
                .andExpect(jsonPath("$.data.totalLoanAmount").value(300000))
                .andExpect(jsonPath("$.data.exactRateType").value("FIXED"))
                .andExpect(jsonPath("$.data.lockDate").doesNotExist())
                .andExpect(jsonPath("$.data.lockedBy").doesNotExist())
                .andExpect(jsonPath("$.data.currentExpiration").doesNotExist());
    }

    @Test
    void getAdjustments_neverPriced_returnsEmptyList() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getLockHistory_neverLocked_returnsEmptyList() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getPricing_unknownLoan_returns404() throws Exception {
        mvc.perform(get("/api/loans/{id}/pricing", UUID.randomUUID()).with(lo()))
                .andExpect(status().isNotFound());
    }
}
