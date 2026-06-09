package com.msfg.los.reo.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReoSummaryIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private String createLoan() throws Exception {
        MvcResult res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private void addReoFull(String loanId, String body) throws Exception {
        mvc.perform(post("/api/loans/{l}/reo", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // --- crown jewel: 2 rows → all 5 totals match independent JDBC sums ---

    @Test
    void summaryTotalsMatchIndependentJdbcSums() throws Exception {
        String loanId = createLoan();

        // Row 1: SINGLE_FAMILY RETAINED, all expense components populated
        // marketValue=300000, rental=1500, taxes=200, insurance=100, hoa=50, maint=75
        // mortgageUnpaidBalance=180000, mortgageMonthlyPayment=1200
        addReoFull(loanId, """
                {
                  "propertyType": "SINGLE_FAMILY",
                  "propertyStatus": "RETAINED",
                  "isSubjectProperty": true,
                  "marketValue": 300000,
                  "grossMonthlyRentalIncome": 1500,
                  "monthlyTaxes": 200,
                  "monthlyInsurance": 100,
                  "monthlyHoaDues": 50,
                  "monthlyMaintenance": 75,
                  "mortgageUnpaidBalance": 180000,
                  "mortgageMonthlyPayment": 1200
                }
                """);

        // Row 2: CONDOMINIUM RENTAL, partial expense components (hoaDues + maint null)
        // marketValue=150000, rental=900, taxes=120, insurance=80, hoa=null, maint=null
        // mortgageUnpaidBalance=95000, mortgageMonthlyPayment=750
        addReoFull(loanId, """
                {
                  "propertyType": "CONDOMINIUM",
                  "propertyStatus": "RENTAL",
                  "isSubjectProperty": false,
                  "marketValue": 150000,
                  "grossMonthlyRentalIncome": 900,
                  "monthlyTaxes": 120,
                  "monthlyInsurance": 80,
                  "mortgageUnpaidBalance": 95000,
                  "mortgageMonthlyPayment": 750
                }
                """);

        MvcResult result = mvc.perform(get("/api/loans/{l}/reo/summary", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // --- totalMarketValue = 300000 + 150000 = 450000 ---
        Number mvRaw = JsonPath.read(body, "$.data.totalMarketValue");
        BigDecimal totalMarketValue = new BigDecimal(mvRaw.toString());

        BigDecimal dbMarketValue = jdbc.queryForObject(
                "select coalesce(sum(market_value), 0) from reo where loan_id = ?::uuid",
                BigDecimal.class, loanId);
        assertThat(dbMarketValue).isNotNull();
        assertThat(totalMarketValue.compareTo(dbMarketValue)).isEqualTo(0);

        // --- totalGrossMonthlyRentalIncome = 1500 + 900 = 2400 ---
        Number rentalRaw = JsonPath.read(body, "$.data.totalGrossMonthlyRentalIncome");
        BigDecimal totalRental = new BigDecimal(rentalRaw.toString());

        BigDecimal dbRental = jdbc.queryForObject(
                "select coalesce(sum(gross_monthly_rental_income), 0) from reo where loan_id = ?::uuid",
                BigDecimal.class, loanId);
        assertThat(dbRental).isNotNull();
        assertThat(totalRental.compareTo(dbRental)).isEqualTo(0);

        // --- totalMonthlyExpenses:
        //   row1: 200+100+50+75 = 425
        //   row2: 120+80+0+0   = 200
        //   total = 625
        // JDBC uses coalesce per component to handle nulls ---
        Number expRaw = JsonPath.read(body, "$.data.totalMonthlyExpenses");
        BigDecimal totalExpenses = new BigDecimal(expRaw.toString());

        BigDecimal dbExpenses = jdbc.queryForObject(
                "select coalesce(sum(coalesce(monthly_taxes,0)" +
                        "+coalesce(monthly_insurance,0)" +
                        "+coalesce(monthly_hoa_dues,0)" +
                        "+coalesce(monthly_maintenance,0)),0)" +
                        " from reo where loan_id = ?::uuid",
                BigDecimal.class, loanId);
        assertThat(dbExpenses).isNotNull();
        assertThat(totalExpenses.compareTo(dbExpenses)).isEqualTo(0);

        // --- totalMortgageUnpaidBalance = 180000 + 95000 = 275000 ---
        Number balRaw = JsonPath.read(body, "$.data.totalMortgageUnpaidBalance");
        BigDecimal totalBalance = new BigDecimal(balRaw.toString());

        BigDecimal dbBalance = jdbc.queryForObject(
                "select coalesce(sum(mortgage_unpaid_balance), 0) from reo where loan_id = ?::uuid",
                BigDecimal.class, loanId);
        assertThat(dbBalance).isNotNull();
        assertThat(totalBalance.compareTo(dbBalance)).isEqualTo(0);

        // --- totalMonthlyMortgagePayment = 1200 + 750 = 1950 ---
        Number payRaw = JsonPath.read(body, "$.data.totalMonthlyMortgagePayment");
        BigDecimal totalPayment = new BigDecimal(payRaw.toString());

        BigDecimal dbPayment = jdbc.queryForObject(
                "select coalesce(sum(mortgage_monthly_payment), 0) from reo where loan_id = ?::uuid",
                BigDecimal.class, loanId);
        assertThat(dbPayment).isNotNull();
        assertThat(totalPayment.compareTo(dbPayment)).isEqualTo(0);
    }

    // --- cross-org JWT → 404 ---

    @Test
    void crossOrgCannotSeeSummary404() throws Exception {
        String loanId = createLoan();

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(get("/api/loans/{l}/reo/summary", loanId).with(otherOrg))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/reo/summary", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
