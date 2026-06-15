package com.msfg.los.disclosures.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.disclosures.timing.BusinessDayCalculator;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BusinessDayCalculator businessDayCalculator;

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

    /** Section-A origination fee (ZERO bucket) + Section-E UNAFFILIATED fee (TEN_PERCENT bucket); returns [zeroId, tenId]. */
    private String[] addTolerableFees(String loSub, String loanId) throws Exception {
        var zero = mvc.perform(post("/api/loans/{id}/fees", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Origination Fee\",\"amount\":1000,"
                                + "\"paidTo\":\"CREDITOR\"}"))
                .andExpect(status().isCreated()).andReturn();
        var ten = mvc.perform(post("/api/loans/{id}/fees", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"E\",\"label\":\"Title Service Fee\",\"amount\":1000,"
                                + "\"paidTo\":\"UNAFFILIATED\"}"))
                .andExpect(status().isCreated()).andReturn();
        return new String[] {
                JsonPath.read(zero.getResponse().getContentAsString(), "$.data.id"),
                JsonPath.read(ten.getResponse().getContentAsString(), "$.data.id")};
    }

    private void patchFeeAmount(String loSub, String loanId, String feeId, int amount) throws Exception {
        mvc.perform(patch("/api/loans/{loanId}/fees/{feeId}", loanId, feeId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isOk());
    }

    private void issueCd(String loSub, String loanId) throws Exception {
        mvc.perform(post("/api/loans/{loanId}/disclosures/closing-disclosure", loanId)
                        .with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
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

    /**
     * RE-REVIEW DEFECT: /timing must IGNORE ERROR rows. A failed LE issuance writes an ERROR row
     * (highest disclosure_version, delivered_at=null, earliest_consummation_date=null). The unfiltered
     * latest-LE lookup used to pick that ERROR row, so leDeliveredOnTime did
     * {@code errorRow.getDeliveredAt().atZone(...)} → NPE → 500 on GET /timing.
     *
     * <p>Discriminating sequence: issue a SUCCESSFUL LE (v1, delivered_at + earliest set), then
     * JDBC-insert an ERROR LE row at a HIGHER version (v99, delivered_at null) for the same loan/org.
     * Buggy code reads the ERROR row as the latest LE → NPE/500. Fixed (status-filtered latestLe/cd),
     * /timing reflects only the SUCCESSFUL LE → 200, leDeliveryDeadline present, leDeliveredOnTime non-null.
     */
    @Test
    void timingIgnoresErrorLeRow() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addFees(lo, loanId);
        issueLe(lo, loanId); // SUCCESSFUL LE v1 — delivered_at + earliest_consummation_date set

        // Failed-issuance shape: ERROR LE at the HIGHEST version, delivered_at + earliest left NULL
        // by default. Mirrors what DisclosureIssuanceErrorRecorder persists on a vendor outage.
        jdbc.update(
                "insert into disclosure_issuance (id, org_id, loan_id, kind, disclosure_version, status) "
                        + "values (?::uuid, ?::uuid, ?::uuid, 'LOAN_ESTIMATE', 99, 'ERROR')",
                UUID.randomUUID().toString(), DEFAULT_ORG, loanId);

        mvc.perform(get("/api/loans/{loanId}/disclosures/timing", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk()) // was 500 (NPE) before the status filter
                .andExpect(jsonPath("$.data.leDeliveryDeadline", notNullValue()))
                .andExpect(jsonPath("$.data.leDeliveredOnTime", notNullValue()));
    }

    /**
     * BLOCKER 2 (HIGH): /tolerance must measure the CD's ACTUAL charges (its snapshot) against the
     * latest good-faith LE baseline (TRID 1026.19(e)(3)) — NOT the live LE re-assembly.
     *
     * <p>Discriminating sequence: issue LE (baseline zero=1000, ten=1000) → RAISE both fees → issue
     * CD (snapshots the raised zero=1500, ten=1200) → then LOWER the live fees back to 1000/1000.
     * The CORRECT current set is the CD's snapshot (raised), so the comparison is OUT of tolerance
     * (zeroExcess 500, tenExcess 100). The OLD LE-vs-LE wiring uses the live re-assembly as "current"
     * — which is now back at baseline — and would wrongly report withinTolerance=true / zero excess.
     */
    @Test
    void toleranceComparesCdSnapshotNotLiveLe() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String[] ids = addTolerableFees(lo, loanId);
        String zeroFee = ids[0];
        String tenFee = ids[1];

        // Baseline LE snapshots zero=1000, ten=1000.
        issueLe(lo, loanId);

        // Raise the fees, then issue the CD which snapshots the RAISED amounts (zero=1500, ten=1200).
        patchFeeAmount(lo, loanId, zeroFee, 1500); // zero-tolerance: +500 over baseline
        patchFeeAmount(lo, loanId, tenFee, 1200);  // ten aggregate 1200 > 1100 (110% of 1000)
        issueCd(lo, loanId);

        // Lower the live fees back to baseline. Only the CD snapshot still carries the overage now —
        // a live re-assembly (the bug) would see 1000/1000 and report within tolerance.
        patchFeeAmount(lo, loanId, zeroFee, 1000);
        patchFeeAmount(lo, loanId, tenFee, 1000);

        mvc.perform(get("/api/loans/{loanId}/disclosures/tolerance", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comparisonVsBaselineLe.withinTolerance").value(false))
                .andExpect(jsonPath("$.data.comparisonVsBaselineLe.zeroBucketExcess").value(500.0))
                .andExpect(jsonPath("$.data.comparisonVsBaselineLe.tenPercentExcess").value(100.0));
    }

    /**
     * BLOCKER 3 (HIGH): the LE 3-general-business-day delivery deadline (1026.19(e)(1)(iii)) must be
     * surfaced on /timing. Deadline = application date (loan createdAt, UTC) + 3 GENERAL business
     * days, recomputed in-test via the same BusinessDayCalculator; leDeliveredOnTime is non-null once
     * an LE exists.
     */
    @Test
    void timingSurfacesLeDeliveryDeadline() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addFees(lo, loanId);
        issueLe(lo, loanId);

        // Application-date proxy = loan createdAt (UTC date). Recompute the expected deadline independently.
        java.time.LocalDate appDate = jdbc.queryForObject(
                "select (created_at at time zone 'UTC')::date from loan where id = ?::uuid",
                java.time.LocalDate.class, loanId);
        java.time.LocalDate expected = businessDayCalculator.addBusinessDays(
                appDate, 3,
                com.msfg.los.disclosures.domain.BusinessDayType.GENERAL,
                com.msfg.los.disclosures.timing.GeneralBusinessDayConfig.DEFAULT);

        mvc.perform(get("/api/loans/{loanId}/disclosures/timing", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.leDeliveryDeadline").value(expected.toString()))
                .andExpect(jsonPath("$.data.leDeliveredOnTime", notNullValue()));
    }
}
