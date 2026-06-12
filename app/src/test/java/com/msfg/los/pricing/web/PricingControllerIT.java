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

    // ── Control-your-price (lock) ─────────────────────────────────────────────

    String cypBody() {
        return """
                {"rate": 6.5, "commitmentDays": 30, "compensationPayerType": "LENDER_PAID"}
                """;
    }

    @Test
    void controlYourPrice_locksLoan_persistsGoldenAdjustments_andAppendsEvent() throws Exception {
        String loanId = priceableLoan();

        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockStatus").value("LOCKED"))
                .andExpect(jsonPath("$.data.interestRate").value(6.5))
                .andExpect(jsonPath("$.data.commitmentDays").value(30))
                .andExpect(jsonPath("$.data.extensionDaysTotal").value(0))
                .andExpect(jsonPath("$.data.compensationPayerType").value("LENDER_PAID"))
                .andExpect(jsonPath("$.data.lockedBy").value(LO))
                .andExpect(jsonPath("$.data.interviewerEmail").value("lo@msfg.test"))
                .andExpect(jsonPath("$.data.lockDate").exists())
                .andExpect(jsonPath("$.data.currentExpiration").exists());

        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].name").value("Base Price"))
                .andExpect(jsonPath("$.data[0].rowType").value("BASE"))
                .andExpect(jsonPath("$.data[0].adjustmentPercent").value(-0.375))
                .andExpect(jsonPath("$.data[0].dollarAmount").value(-1125.00))
                .andExpect(jsonPath("$.data[1].name").value("FICO/LTV Adjustment"))
                .andExpect(jsonPath("$.data[1].adjustmentPercent").value(0.500))
                .andExpect(jsonPath("$.data[1].dollarAmount").value(1500.00))
                .andExpect(jsonPath("$.data[3].name").value("Final Price"))
                .andExpect(jsonPath("$.data[3].adjustmentPercent").value(0.125))
                .andExpect(jsonPath("$.data[3].dollarAmount").value(375.00))
                .andExpect(jsonPath("$.data[5].name").value("Final Price After Compensation"))
                .andExpect(jsonPath("$.data[5].dollarAmount").value(3375.00));

        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].action").value("CONTROL_YOUR_PRICE"))
                .andExpect(jsonPath("$.data[0].actor").value(LO))
                .andExpect(jsonPath("$.data[0].rate").value(6.5));
    }

    @Test
    void controlYourPrice_repriceWhileLocked_replacesAdjustments_secondEvent() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());

        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 7.0, \"commitmentDays\": 15, \"compensationPayerType\": \"BORROWER_PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestRate").value(7.0))
                .andExpect(jsonPath("$.data.commitmentDays").value(15));

        // Re-quote replaced (still 6 rows, not 12); rate 7.0/15d: Base = -0-0 = 0.000
        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].ordinal").value(1))
                .andExpect(jsonPath("$.data[0].adjustmentPercent").value(0.000));

        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void getPricing_onBareLoan_returnsNotLockedWithNulls() throws Exception {
        String loanId = createLoan();   // never patched — the FE's literal first page-load
        mvc.perform(get("/api/loans/{id}/pricing", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockStatus").value("NOT_LOCKED"))
                .andExpect(jsonPath("$.data.interestRate").doesNotExist())
                .andExpect(jsonPath("$.data.totalLoanAmount").doesNotExist())
                .andExpect(jsonPath("$.data.exactRateType").doesNotExist());
    }

    // ── Validation: one test per rule branch (assert $.fields.<name>) ─────────

    @Test
    void cyp_missingRate_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commitmentDays\": 30, \"compensationPayerType\": \"LENDER_PAID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.rate").exists());
    }

    @Test
    void cyp_rateTooLow_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 0.05, \"commitmentDays\": 30, \"compensationPayerType\": \"LENDER_PAID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.rate").exists());
    }

    @Test
    void cyp_missingCommitmentDays_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 6.5, \"compensationPayerType\": \"LENDER_PAID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.commitmentDays").exists());
    }

    @Test
    void cyp_disallowedCommitmentDays_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 6.5, \"commitmentDays\": 17, \"compensationPayerType\": \"LENDER_PAID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.commitmentDays").exists());
    }

    @Test
    void cyp_missingCompensationPayerType_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 6.5, \"commitmentDays\": 30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.compensationPayerType").exists());
    }

    // ── Domain conflicts ──────────────────────────────────────────────────────

    @Test
    void cyp_loanWithoutBaseAmount_409_LOAN_NOT_PRICEABLE() throws Exception {
        String loanId = createLoan();   // no baseLoanAmount patched
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_NOT_PRICEABLE"));
    }

    @Test
    void cyp_terminalLoan_409() throws Exception {
        String loanId = priceableLoan();
        // STARTED -> CANCELLED is legal in one hop (any non-terminal status may cancel, no role gate).
        // Body key is targetStatus per loan-core's TransitionRequest record.
        mvc.perform(post("/api/loans/{id}/status", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"CANCELLED\",\"reason\":\"test\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_STATE_CONFLICT"));
    }

    // ── Cross-tenant isolation ────────────────────────────────────────────────

    @Test
    void crossTenant_foreignOrgJwt_getsNotFoundOnReadsAndActions() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());

        RequestPostProcessor foreign = jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", "00000000-0000-0000-0000-0000000000bb"))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(get("/api/loans/{id}/pricing", loanId).with(foreign))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(foreign))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(foreign))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(foreign)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"additionalDays\": 15}"))
                .andExpect(status().isNotFound());
    }
}
