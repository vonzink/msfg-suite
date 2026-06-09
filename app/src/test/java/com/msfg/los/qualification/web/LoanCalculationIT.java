package com.msfg.los.qualification.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Crown-jewel + edge-case integration test for GET /api/loans/{loanId}/calculations.
 *
 * Each test uses a SEPARATE loan so edge cases are fully isolated.
 * Crown-jewel assertions are exact to the cent / scale-3 ratio.
 */
class LoanCalculationIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    // ── JWT helpers ─────────────────────────────────────────────────────────

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    // ── Setup helpers ───────────────────────────────────────────────────────

    /** Create loan with given loanPurpose; returns loanId. */
    private String createLoan(String loanPurpose) throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"%s\",\"loanOfficerId\":\"%s\"}".formatted(loanPurpose, LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** PATCH §4 fields onto the loan. */
    private void patchLoan(String loanId, String jsonBody) throws Exception {
        mvc.perform(patch("/api/loans/{id}", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk());
    }

    /** Add a borrower, return borrowerId. */
    private String addBorrower(String loanId) throws Exception {
        var res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"primary\":true}"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Add a CURRENT W2 employment, return employmentId. */
    private String addEmployment(String loanId, String borrowerId) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerName\":\"ACME Corp\",\"employmentStatus\":\"CURRENT\",\"startDate\":\"2020-01-01\"}"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Add an income entry tied to an employment, return incomeId. */
    private void addIncome(String loanId, String borrowerId, String incomeType,
                           int monthlyAmount, String employmentId) throws Exception {
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"%s\",\"monthlyAmount\":%d,\"employmentId\":\"%s\"}"
                                .formatted(incomeType, monthlyAmount, employmentId)))
                .andExpect(status().isCreated());
    }

    /** Add a DTI-included INSTALLMENT liability, return liabilityId. */
    private void addLiability(String loanId, String borrowerId, int monthlyPayment) throws Exception {
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liabilityType\":\"INSTALLMENT\",\"monthlyPayment\":%d,\"includeInDti\":true}"
                                .formatted(monthlyPayment)))
                .andExpect(status().isCreated());
    }

    /** Add an REO, return reoId. */
    private String addReo(String loanId, int gross, int mortgagePayment) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/reo", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"propertyType\":\"SINGLE_FAMILY\",\"propertyStatus\":\"RENTAL\"," +
                                  "\"marketValue\":100000,\"grossMonthlyRentalIncome\":%d," +
                                  "\"mortgageMonthlyPayment\":%d}").formatted(gross, mortgagePayment)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // ── Helper: assert a BigDecimal JSON field by exact compareTo ───────────

    /** Read a $.data.field as a String and compare to expected using BigDecimal.compareTo. */
    private void assertDecimalEquals(String responseBody, String jsonPath, String expected) {
        Object raw = com.jayway.jsonpath.JsonPath.read(responseBody, jsonPath);
        BigDecimal actual = new BigDecimal(raw.toString());
        BigDecimal exp    = new BigDecimal(expected);
        if (actual.compareTo(exp) != 0) {
            throw new AssertionError(
                    "Expected " + jsonPath + " == " + expected + " but got " + actual);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Crown-jewel: fully-worked example with exact assertions
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void crownJewel_workedExampleAllFiguresExact() throws Exception {

        // ── 1. Create PURCHASE loan ──────────────────────────────────────────
        String loanId = createLoan("PURCHASE");

        // ── 2. Patch §4 fields ───────────────────────────────────────────────
        patchLoan(loanId, """
                {
                  "baseLoanAmount": 300000,
                  "interestRate": 6.5,
                  "loanTermMonths": 360,
                  "salesPrice": 375000,
                  "appraisedValue": 380000,
                  "proposedTaxesMonthly": 400,
                  "proposedHazardInsuranceMonthly": 120,
                  "proposedHoaDuesMonthly": 0,
                  "proposedMortgageInsuranceMonthly": 90
                }
                """);

        // ── 3. Add borrower + employment + income (BASE 5000 + OVERTIME 4000 = 9000) ──
        String borrowerId  = addBorrower(loanId);
        String employmentId = addEmployment(loanId, borrowerId);
        addIncome(loanId, borrowerId, "BASE",     5000, employmentId);
        addIncome(loanId, borrowerId, "OVERTIME", 4000, employmentId);

        // ── 4. Add liability (DTI-included, monthly 650) ─────────────────────
        addLiability(loanId, borrowerId, 650);

        // ── 5. Add REO (gross 2000, mortgage 1200 → net = 0.75×2000−1200 = 300 income) ──
        addReo(loanId, 2000, 1200);

        // ── 6. GET /api/loans/{id}/calculations ──────────────────────────────
        var result = mvc.perform(get("/api/loans/{id}/calculations", loanId).with(lo()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // ── 7. Crown-jewel assertions (EXACT) ────────────────────────────────
        // ltvBasis = min(375000, 380000) = 375000
        assertDecimalEquals(body, "$.data.ltvBasis", "375000");

        // ltv = 300000 / 375000 × 100 = 80.000
        assertDecimalEquals(body, "$.data.ltv", "80.000");

        // monthlyPrincipalInterest: P&I on 300000 @ 6.5% / 12 for 360 months ≈ 1896.20
        assertDecimalEquals(body, "$.data.monthlyPrincipalInterest", "1896.20");

        // proposedHousingExpense = 1896.20 + 400 + 120 + 0 + 90 = 2506.20
        assertDecimalEquals(body, "$.data.proposedHousingExpense", "2506.20");

        // netRentalIncome = 0.75×2000 − 1200 = 300.00 (positive → income)
        assertDecimalEquals(body, "$.data.netRentalIncome", "300.00");

        // totalMonthlyIncome = 9000 (base) + 300 (rental) = 9300.00
        assertDecimalEquals(body, "$.data.totalMonthlyIncome", "9300.00");

        // totalMonthlyDebts = 650 (liabilities) + 0 (no netRentalDebt) = 650.00
        assertDecimalEquals(body, "$.data.totalMonthlyDebts", "650.00");

        // frontEndDti = 2506.20 / 9300.00 × 100 = 26.948... → scale 3 HALF_UP = 26.948
        assertDecimalEquals(body, "$.data.frontEndDti", "26.948");

        // backEndDti = (2506.20 + 650.00) / 9300.00 × 100 = 3156.20/9300 × 100 = 33.938... → 33.938
        assertDecimalEquals(body, "$.data.backEndDti", "33.938");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge case 1: no income rows → frontEndDti + backEndDti null, ltv still computed
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void edgeCase_noIncome_dtiNullLtvStillComputed() throws Exception {
        String loanId = createLoan("PURCHASE");
        patchLoan(loanId, """
                {
                  "baseLoanAmount": 300000,
                  "interestRate": 6.5,
                  "loanTermMonths": 360,
                  "salesPrice": 375000,
                  "appraisedValue": 380000,
                  "proposedTaxesMonthly": 400,
                  "proposedHazardInsuranceMonthly": 120,
                  "proposedHoaDuesMonthly": 0,
                  "proposedMortgageInsuranceMonthly": 90
                }
                """);
        // No income rows added.

        mvc.perform(get("/api/loans/{id}/calculations", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ltv").isNotEmpty())
                .andExpect(jsonPath("$.data.monthlyPrincipalInterest").isNotEmpty())
                .andExpect(jsonPath("$.data.proposedHousingExpense").isNotEmpty())
                // totalMonthlyIncome == 0 → percentRatio returns null
                .andExpect(jsonPath("$.data.frontEndDti").value(nullValue()))
                .andExpect(jsonPath("$.data.backEndDti").value(nullValue()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge case 2: refi value basis — appraised used (not lesser-of)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void edgeCase_refiLoanUsesAppraisedForLtvBasis() throws Exception {
        // Create loan with loanPurpose=REFINANCE; no salesPrice
        String loanId = createLoan("REFINANCE");
        patchLoan(loanId, """
                {
                  "baseLoanAmount": 300000,
                  "interestRate": 6.5,
                  "loanTermMonths": 360,
                  "appraisedValue": 380000
                }
                """);

        var result = mvc.perform(get("/api/loans/{id}/calculations", loanId).with(lo()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // ltvBasis = appraised = 380000 (NOT lesser-of with missing salesPrice)
        assertDecimalEquals(body, "$.data.ltvBasis", "380000");

        // ltv = 300000 / 380000 × 100 = 78.947...  → scale 3 = 78.947
        assertDecimalEquals(body, "$.data.ltv", "78.947");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge case 3: second loan → cltv > ltv
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void edgeCase_secondLoanCltvExceedsLtv() throws Exception {
        String loanId = createLoan("PURCHASE");
        // base 300000, second 30000, value basis 375000
        // ltv  = 300000/375000 × 100 = 80.000
        // cltv = 330000/375000 × 100 = 88.000
        patchLoan(loanId, """
                {
                  "baseLoanAmount": 300000,
                  "secondLoanAmount": 30000,
                  "interestRate": 6.5,
                  "loanTermMonths": 360,
                  "salesPrice": 375000,
                  "appraisedValue": 380000
                }
                """);

        var result = mvc.perform(get("/api/loans/{id}/calculations", loanId).with(lo()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertDecimalEquals(body, "$.data.ltv",  "80.000");
        assertDecimalEquals(body, "$.data.cltv", "88.000");

        // cltv > ltv
        BigDecimal ltv  = new BigDecimal(com.jayway.jsonpath.JsonPath.read(body, "$.data.ltv").toString());
        BigDecimal cltv = new BigDecimal(com.jayway.jsonpath.JsonPath.read(body, "$.data.cltv").toString());
        if (cltv.compareTo(ltv) <= 0) {
            throw new AssertionError("Expected cltv (" + cltv + ") > ltv (" + ltv + ")");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge case 4: negative net rental → netRentalDebt added to debts; income NOT reduced
    // gross 1000, mortgage 1500 → net = 0.75×1000−1500 = 750−1500 = −750 → netRentalDebt = 750.00
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void edgeCase_negativeNetRentalBecomesDebtNotIncomeReduction() throws Exception {
        String loanId = createLoan("PURCHASE");
        patchLoan(loanId, """
                {
                  "baseLoanAmount": 300000,
                  "interestRate": 6.5,
                  "loanTermMonths": 360,
                  "salesPrice": 375000,
                  "appraisedValue": 380000,
                  "proposedTaxesMonthly": 400,
                  "proposedHazardInsuranceMonthly": 120,
                  "proposedHoaDuesMonthly": 0,
                  "proposedMortgageInsuranceMonthly": 90
                }
                """);

        // Add income so we can observe totalMonthlyIncome is NOT reduced by negative rental
        String borrowerId   = addBorrower(loanId);
        String employmentId = addEmployment(loanId, borrowerId);
        addIncome(loanId, borrowerId, "BASE", 9000, employmentId);

        // REO: gross 1000, mortgage 1500 → net = −750 → netRentalDebt = 750
        addReo(loanId, 1000, 1500);

        var result = mvc.perform(get("/api/loans/{id}/calculations", loanId).with(lo()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // netRentalIncome == 0 (no positive net)
        assertDecimalEquals(body, "$.data.netRentalIncome", "0.00");

        // netRentalDebt == 750.00
        assertDecimalEquals(body, "$.data.netRentalDebt", "750.00");

        // totalMonthlyIncome == 9000 (income NOT reduced)
        assertDecimalEquals(body, "$.data.totalMonthlyIncome", "9000.00");

        // totalMonthlyDebts == 750.00 (just the rental debt, no liability added)
        assertDecimalEquals(body, "$.data.totalMonthlyDebts", "750.00");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge case 5: zero interest rate → P&I = base / term
    // base=360000, term=360 → P&I = 1000.00
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void edgeCase_zeroInterestRate_piEqualsBaseOverTerm() throws Exception {
        String loanId = createLoan("PURCHASE");
        patchLoan(loanId, """
                {
                  "baseLoanAmount": 360000,
                  "interestRate": 0,
                  "loanTermMonths": 360,
                  "salesPrice": 450000,
                  "appraisedValue": 460000
                }
                """);

        var result = mvc.perform(get("/api/loans/{id}/calculations", loanId).with(lo()))
                .andExpect(status().isOk())
                .andReturn();

        // P&I = 360000 / 360 = 1000.00
        assertDecimalEquals(result.getResponse().getContentAsString(),
                "$.data.monthlyPrincipalInterest", "1000.00");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge case 6: no rate/term → P&I null → proposedHousingExpense null → DTI null
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void edgeCase_noRateOrTerm_piNullHousingNullDtiNull() throws Exception {
        String loanId = createLoan("PURCHASE");
        patchLoan(loanId, """
                {
                  "baseLoanAmount": 300000,
                  "salesPrice": 375000,
                  "appraisedValue": 380000,
                  "proposedTaxesMonthly": 400,
                  "proposedHazardInsuranceMonthly": 120
                }
                """);
        // No interestRate, no loanTermMonths.

        mvc.perform(get("/api/loans/{id}/calculations", loanId).with(lo()))
                .andExpect(status().isOk())
                // ltv still computed
                .andExpect(jsonPath("$.data.ltv").isNotEmpty())
                // P&I null
                .andExpect(jsonPath("$.data.monthlyPrincipalInterest").value(nullValue()))
                // proposedHousingExpense null because P&I null
                .andExpect(jsonPath("$.data.proposedHousingExpense").value(nullValue()))
                // DTI null (housing null)
                .andExpect(jsonPath("$.data.frontEndDti").value(nullValue()))
                .andExpect(jsonPath("$.data.backEndDti").value(nullValue()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge case 7: no value (no salesPrice, no appraisedValue) → ltv null
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void edgeCase_noValue_ltvNull() throws Exception {
        String loanId = createLoan("PURCHASE");
        patchLoan(loanId, """
                {
                  "baseLoanAmount": 300000,
                  "interestRate": 6.5,
                  "loanTermMonths": 360
                }
                """);
        // No salesPrice, no appraisedValue → ltvBasis null → ltv null.

        mvc.perform(get("/api/loans/{id}/calculations", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ltvBasis").value(nullValue()))
                .andExpect(jsonPath("$.data.ltv").value(nullValue()))
                // P&I still computed (base + rate + term present)
                .andExpect(jsonPath("$.data.monthlyPrincipalInterest").isNotEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge case 8: tenant scope
    //   a) cross-org JWT → 404
    //   b) no token     → 401
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void edgeCase_crossOrgJwt_returns404() throws Exception {
        String loanId = createLoan("PURCHASE");

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(get("/api/loans/{id}/calculations", loanId).with(otherOrg))
                .andExpect(status().isNotFound());
    }

    @Test
    void edgeCase_noToken_returns401() throws Exception {
        mvc.perform(get("/api/loans/{id}/calculations", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge case 9: null baseLoanAmount → ltv, cltv, tltv ALL null
    //   secondLoanAmount present; salesPrice + appraisedValue present (ltvBasis non-null).
    //   Bug: old code coerced null base to 0 via nz(base), yielding cltv = 8.000 instead of null.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void nullBaseLoanAmountYieldsNullLtvFamily() throws Exception {
        String loanId = createLoan("PURCHASE");
        // secondLoanAmount present, value basis present, but NO baseLoanAmount
        patchLoan(loanId, """
                {
                  "secondLoanAmount": 30000,
                  "salesPrice": 375000,
                  "appraisedValue": 375000
                }
                """);

        mvc.perform(get("/api/loans/{id}/calculations", loanId).with(lo()))
                .andExpect(status().isOk())
                // totalLoanAmount null (base is null)
                .andExpect(jsonPath("$.data.totalLoanAmount").value(nullValue()))
                // ltv null (base null → percentRatio returns null)
                .andExpect(jsonPath("$.data.ltv").value(nullValue()))
                // cltv null — guard: base==null → null (was WRONG: returned 8.000)
                .andExpect(jsonPath("$.data.cltv").value(nullValue()))
                // tltv null (assigned from cltv)
                .andExpect(jsonPath("$.data.tltv").value(nullValue()));
    }
}
