package com.msfg.los.disclosures.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Closing Disclosure issuance + reset detection (Disclosures Task 10). A CD goes through the same
 * kind-generic issuance path as the LE, but each new CD version is compared against the prior CD to
 * detect the three TRID re-disclosure triggers (1026.19(f)(2)(ii) + 1026.22(a)):
 * APR_INACCURATE (band 0.125 regular), PRODUCT_CHANGED, PREPAYMENT_PENALTY_ADDED.
 *
 * <p>The stub vendor's APR is a monotone placeholder: interestRate + (prepaidFinanceCharges /
 * loanAmount) * 100, where prepaidFinanceCharges = sum of ZERO-bucket fees (a Section-A fee paid to
 * the CREDITOR is a ZERO-bucket fee). So bumping that fee's amount moves the APR by a known amount;
 * the band is recomputed independently below.
 */
class ClosingDisclosureIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /** Loan amount = 300000; stub APR premium = prepaid/loanAmount*100, band = 0.125 (regular). */
    private static final BigDecimal LOAN_AMOUNT = new BigDecimal("300000");
    private static final BigDecimal BAND = new BigDecimal("0.125");

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
                                 "appraisedValue":400000,"interestRate":6.5,"loanTermMonths":360,
                                 "mortgageType":"CONVENTIONAL"}"""))
                .andExpect(status().isOk());
        return loanId;
    }

    /** Adds one Section-A origination fee paid to CREDITOR (ZERO bucket) and returns its id. */
    private String addZeroBucketFee(String loSub, String loanId, int amount) throws Exception {
        var res = mvc.perform(post("/api/loans/{id}/fees", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Origination Fee\",\"amount\":" + amount
                                + ",\"paidTo\":\"CREDITOR\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private void patchFeeAmount(String loSub, String loanId, String feeId, int amount) throws Exception {
        mvc.perform(patch("/api/loans/{loanId}/fees/{feeId}", loanId, feeId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isOk());
    }

    private String postCd(String loSub, String loanId, String body) throws Exception {
        return mvc.perform(post("/api/loans/{loanId}/disclosures/closing-disclosure", loanId)
                        .with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private Integer issuanceCount(String loanId) {
        return jdbc.queryForObject(
                "select count(*) from disclosure_issuance where loan_id = ?::uuid", Integer.class, loanId);
    }

    private Integer resetReasonsLen(String loanId, int version) {
        return jdbc.queryForObject(
                "select jsonb_array_length(reset_reasons) from disclosure_issuance "
                        + "where loan_id = ?::uuid and disclosure_version = ?",
                Integer.class, loanId, version);
    }

    /** A prepaid delta of $375 moves the APR by exactly the band; assert our test deltas are on the right side. */
    private BigDecimal aprDeltaForPrepaidDelta(int prepaidDelta) {
        return new BigDecimal(prepaidDelta).divide(LOAN_AMOUNT, 10, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    /**
     * CROWN JEWEL: a CD is re-issued four times, each exercising a distinct reset outcome —
     * (i) APR past the band → APR_INACCURATE, (ii) product change → PRODUCT_CHANGED,
     * (iii) prepayment penalty added → PREPAYMENT_PENALTY_ADDED, (iv) a within-band fee tweak → no reset.
     */
    @Test
    void closingDisclosureResetTriggers() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String feeId = addZeroBucketFee(lo, loanId, 1200);

        // v1 — baseline CD, no prior CD to compare against → no reset.
        String v1 = postCd(lo, loanId, "{}");
        assertThat((Integer) JsonPath.read(v1, "$.data.version")).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(v1, "$.data.resetTriggered")).isFalse();

        // (i) APR past the band. Bump the ZERO-bucket fee far past 0.125 worth of APR.
        // prepaid delta = 250000-1200 = 248800 → APR delta ≈ 82.9 ≫ 0.125 band.
        assertThat(aprDeltaForPrepaidDelta(250000 - 1200)).isGreaterThan(BAND);
        patchFeeAmount(lo, loanId, feeId, 250000);
        String v2 = postCd(lo, loanId, "{}");
        assertThat((Integer) JsonPath.read(v2, "$.data.version")).isEqualTo(2);
        assertThat((Boolean) JsonPath.read(v2, "$.data.resetTriggered")).isTrue();
        assertThat((java.util.List<String>) JsonPath.read(v2, "$.data.resetReasons"))
                .contains("APR_INACCURATE");

        // (ii) Product change: switch mortgageType CONVENTIONAL → FHA. Fee unchanged → APR steady.
        mvc.perform(patch("/api/loans/{id}", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mortgageType\":\"FHA\"}"))
                .andExpect(status().isOk());
        String v3 = postCd(lo, loanId, "{}");
        assertThat((Boolean) JsonPath.read(v3, "$.data.resetTriggered")).isTrue();
        assertThat((java.util.List<String>) JsonPath.read(v3, "$.data.resetReasons"))
                .contains("PRODUCT_CHANGED");

        // (iii) Prepayment penalty added: prior CD (v3) had false; body sets true. Product/APR steady.
        String v4 = postCd(lo, loanId, "{\"prepaymentPenalty\":true}");
        assertThat((Boolean) JsonPath.read(v4, "$.data.resetTriggered")).isTrue();
        assertThat((java.util.List<String>) JsonPath.read(v4, "$.data.resetReasons"))
                .contains("PREPAYMENT_PENALTY_ADDED");

        // (iv) Within-band fee tweak: 250000 → 250100 (prepaid delta 100 → APR delta ≈ 0.033 < band).
        // No body → effective prepay falls back to assembled false; prior CD (v4) had true, so going
        // true→false is NOT an "added" trigger. Product steady (FHA). → no reset.
        assertThat(aprDeltaForPrepaidDelta(100)).isLessThan(BAND);
        patchFeeAmount(lo, loanId, feeId, 250100);
        String v5 = postCd(lo, loanId, "{}");
        assertThat((Boolean) JsonPath.read(v5, "$.data.resetTriggered")).isFalse();
        assertThat((java.util.List<String>) JsonPath.read(v5, "$.data.resetReasons")).isEmpty();

        // JDBC: five issuance rows; triggered versions carry a non-empty reset_reasons jsonb array.
        assertThat(issuanceCount(loanId)).isEqualTo(5);
        assertThat(resetReasonsLen(loanId, 1)).isZero();
        assertThat(resetReasonsLen(loanId, 2)).isGreaterThan(0);
        assertThat(resetReasonsLen(loanId, 5)).isZero();
    }

    @Test
    void crossOrg404() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(post("/api/loans/{loanId}/disclosures/closing-disclosure", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    /**
     * BLOCKER 1 (CRITICAL): an ERROR CD row (apr=null) at a higher disclosure_version must NOT poison
     * prior-CD selection. The next valid CD must compare against the last SUCCESSFULLY-issued CD (v1),
     * not the ERROR row — otherwise priorCd.getApr() is null and reset detection NPEs (500).
     *
     * <p>Sequence: issue a valid CD v1 → JDBC-insert an ERROR CD row (apr=null, version 99) → change
     * the product and issue a valid CD → assert 201, resetTriggered true with PRODUCT_CHANGED, no 500.
     */
    @Test
    void errorCdRow_doesNotPoisonPriorCdSelection() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addZeroBucketFee(lo, loanId, 1200);

        // v1 — valid baseline CD (CONVENTIONAL).
        String v1 = postCd(lo, loanId, "{}");
        assertThat((Integer) JsonPath.read(v1, "$.data.version")).isEqualTo(1);

        // Inject an ERROR CD row with apr=null at a higher version — the shape the recorder persists
        // on a vendor failure. findTopByLoanIdAndKindOrderByDisclosureVersionDesc would return THIS.
        jdbc.update(
                "insert into disclosure_issuance (id, org_id, loan_id, kind, disclosure_version, status) "
                        + "values (?::uuid, ?::uuid, ?::uuid, 'CLOSING_DISCLOSURE', 99, 'ERROR')",
                UUID.randomUUID().toString(), DEFAULT_ORG, loanId);

        // Change the product, then issue a valid CD. With the bug this NPEs (compares against the
        // ERROR row's null apr) → 500. Fixed, it compares against the last GOOD CD (v1) → PRODUCT_CHANGED.
        mvc.perform(patch("/api/loans/{id}", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mortgageType\":\"FHA\"}"))
                .andExpect(status().isOk());

        String next = postCd(lo, loanId, "{}");
        assertThat((Boolean) JsonPath.read(next, "$.data.resetTriggered")).isTrue();
        assertThat((java.util.List<String>) JsonPath.read(next, "$.data.resetReasons"))
                .contains("PRODUCT_CHANGED");
    }
}
