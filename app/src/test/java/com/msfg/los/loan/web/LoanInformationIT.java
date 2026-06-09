package com.msfg.los.loan.web;

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
import static org.hamcrest.Matchers.containsString;

class LoanInformationIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"loanPurpose":"PURCHASE","mortgageType":"CONVENTIONAL","loanOfficerId":"%s"}
                    """.formatted(LO)))
            .andExpect(status().isCreated())
            .andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    @Test
    void patchSection4FieldsThenGetEchosThem() throws Exception {
        String id = createLoan();

        mvc.perform(patch("/api/loans/{id}", id).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "documentationType": "FULL",
                      "interestRate": 6.75,
                      "loanTermMonths": 360,
                      "baseLoanAmount": 400000.00,
                      "financedFeesAmount": 2500.00,
                      "secondLoanAmount": 50000.00,
                      "downPaymentAmount": 80000.00,
                      "qualifyingCreditScore": 740,
                      "proposedTaxesMonthly": 350.00,
                      "proposedHazardInsuranceMonthly": 120.00,
                      "proposedHoaDuesMonthly": 0.00,
                      "proposedMortgageInsuranceMonthly": 180.00,
                      "salesPrice": 500000.00,
                      "appraisedValue": 510000.00,
                      "propertyType": "SINGLE_FAMILY",
                      "occupancyType": "PRIMARY_RESIDENCE",
                      "numberOfUnits": 1
                    }
                    """))
            .andExpect(status().isOk());

        mvc.perform(get("/api/loans/{id}", id).with(lo()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.documentationType").value("FULL"))
            .andExpect(jsonPath("$.data.interestRate").value(6.75))
            .andExpect(jsonPath("$.data.loanTermMonths").value(360))
            .andExpect(jsonPath("$.data.baseLoanAmount").value(400000.00))
            .andExpect(jsonPath("$.data.financedFeesAmount").value(2500.00))
            .andExpect(jsonPath("$.data.secondLoanAmount").value(50000.00))
            .andExpect(jsonPath("$.data.downPaymentAmount").value(80000.00))
            .andExpect(jsonPath("$.data.qualifyingCreditScore").value(740))
            .andExpect(jsonPath("$.data.proposedTaxesMonthly").value(350.00))
            .andExpect(jsonPath("$.data.proposedHazardInsuranceMonthly").value(120.00))
            .andExpect(jsonPath("$.data.proposedHoaDuesMonthly").value(0.00))
            .andExpect(jsonPath("$.data.proposedMortgageInsuranceMonthly").value(180.00))
            .andExpect(jsonPath("$.data.salesPrice").value(500000.00))
            .andExpect(jsonPath("$.data.appraisedValue").value(510000.00))
            .andExpect(jsonPath("$.data.propertyType").value("SINGLE_FAMILY"))
            .andExpect(jsonPath("$.data.occupancyType").value("PRIMARY_RESIDENCE"))
            .andExpect(jsonPath("$.data.numberOfUnits").value(1));
    }

    @Test
    void outOfRangeInterestRateIs400() throws Exception {
        String id = createLoan();
        mvc.perform(patch("/api/loans/{id}", id).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"interestRate\": 30}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("interestRate")));
    }

    @Test
    void outOfRangeLoanTermMonthsIs400() throws Exception {
        String id = createLoan();
        mvc.perform(patch("/api/loans/{id}", id).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanTermMonths\": 600}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("loanTermMonths")));
    }

    @Test
    void outOfRangeQualifyingCreditScoreIs400() throws Exception {
        String id = createLoan();
        mvc.perform(patch("/api/loans/{id}", id).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qualifyingCreditScore\": 900}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("qualifyingCreditScore")));
    }

    @Test
    void outOfRangeNumberOfUnitsIs400() throws Exception {
        String id = createLoan();
        mvc.perform(patch("/api/loans/{id}", id).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"numberOfUnits\": 5}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("numberOfUnits")));
    }

    @Test
    void negativeBaseLoanAmountIs400() throws Exception {
        String id = createLoan();
        mvc.perform(patch("/api/loans/{id}", id).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseLoanAmount\": -1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("baseLoanAmount")));
    }
}
