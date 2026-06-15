package com.msfg.los.disclosures.web;

import com.jayway.jsonpath.JsonPath;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Disclosures Task 9: receipt recording (basis flip ACTUAL + CD-clock recompute), timing rollup,
 * tolerance bucketing, history list, and single-issuance fetch — all loan + tenant scoped. Mirrors
 * LoanEstimateIT idioms (jwt-as helpers, fee/loan seeding).
 */
class DisclosureReadIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    private RequestPostProcessor as(String sub, String role) {
        return as(sub, role, DEFAULT_ORG);
    }

    private RequestPostProcessor as(String sub, String role, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private String createLoan(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
                .andExpect(status().isCreated()).andReturn();
        String loanId = JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
        mvc.perform(patch("/api/loans/{id}", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"noteAmount":300000,"baseLoanAmount":300000,"estimatedValue":400000,
                                 "appraisedValue":400000,"interestRate":6.5,"loanTermMonths":360}"""))
                .andExpect(status().isOk());
        return loanId;
    }

    /** Section-A origination fee (ZERO bucket) + Section-E recording fee (TEN_PERCENT bucket). */
    private void addFees(String loSub, String loanId) throws Exception {
        mvc.perform(post("/api/loans/{id}/fees", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Origination Fee\",\"amount\":1200,"
                                + "\"paidTo\":\"CREDITOR\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/loans/{id}/fees", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"E\",\"label\":\"Recording Fee\",\"amount\":150,"
                                + "\"paidTo\":\"GOVERNMENT\"}"))
                .andExpect(status().isCreated());
    }

    private String issueLe(String loSub, String loanId) throws Exception {
        var res = mvc.perform(post("/api/loans/{loanId}/disclosures/loan-estimate", loanId)
                        .with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    @Test
    void recordReceiptFlipsBasisToActual() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addFees(lo, loanId);
        String discId = issueLe(lo, loanId);

        mvc.perform(post("/api/loans/{loanId}/disclosures/{disclosureId}/receipt", loanId, discId)
                        .with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAt\":\"2026-07-15\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedBasis").value("ACTUAL"))
                .andExpect(jsonPath("$.data.computedReceivedDate").value("2026-07-15"));
    }

    @Test
    void timingRollup() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addFees(lo, loanId);
        issueLe(lo, loanId);

        mvc.perform(get("/api/loans/{loanId}/disclosures/timing", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallEarliestConsummation", notNullValue()));
    }

    @Test
    void toleranceBucketTotals() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addFees(lo, loanId);
        issueLe(lo, loanId);

        mvc.perform(get("/api/loans/{loanId}/disclosures/tolerance", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                // A=CREDITOR → ZERO bucket = 1200; E=GOVERNMENT → ZERO too → 1350 total in ZERO.
                .andExpect(jsonPath("$.data.bucketTotals.ZERO").value(1350))
                .andExpect(jsonPath("$.data.comparisonVsBaselineLe", notNullValue()));
    }

    @Test
    void historyListsIssuance() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addFees(lo, loanId);
        String discId = issueLe(lo, loanId);

        mvc.perform(get("/api/loans/{loanId}/disclosures", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(discId));
    }

    @Test
    void getSingleIssuance() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addFees(lo, loanId);
        String discId = issueLe(lo, loanId);

        mvc.perform(get("/api/loans/{loanId}/disclosures/{disclosureId}", loanId, discId)
                        .with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(discId));
    }

    /** Receipt on a disclosure that belongs to a DIFFERENT loan (same org) → 404. */
    @Test
    void receiptCrossLoan404() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanA = createLoan(lo);
        addFees(lo, loanA);
        String discA = issueLe(lo, loanA);
        String loanB = createLoan(lo);

        mvc.perform(post("/api/loans/{loanId}/disclosures/{disclosureId}/receipt", loanB, discA)
                        .with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAt\":\"2026-07-15\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void receiptCrossOrg404() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addFees(lo, loanId);
        String discId = issueLe(lo, loanId);

        mvc.perform(post("/api/loans/{loanId}/disclosures/{disclosureId}/receipt", loanId, discId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedAt\":\"2026-07-15\"}"))
                .andExpect(status().isNotFound());
    }
}
