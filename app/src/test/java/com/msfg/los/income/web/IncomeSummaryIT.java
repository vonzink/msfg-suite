package com.msfg.los.income.web;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IncomeSummaryIT extends AbstractIntegrationTest {

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

    private String addBorrower(String loanId, String firstName, String lastName, boolean primary) throws Exception {
        MvcResult res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"%s\",\"lastName\":\"%s\",\"primary\":%b}"
                                .formatted(firstName, lastName, primary)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addEmployment(String loanId, String borrowerId) throws Exception {
        MvcResult res = mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId)
                        .with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerName\":\"ACME Corp\",\"employmentStatus\":\"CURRENT\"," +
                                 "\"startDate\":\"2020-01-01\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private void addIncome(String loanId, String borrowerId, String incomeType,
                           int monthlyAmount, String employmentId) throws Exception {
        String body;
        if (employmentId != null) {
            body = "{\"incomeType\":\"%s\",\"monthlyAmount\":%d,\"employmentId\":\"%s\"}"
                    .formatted(incomeType, monthlyAmount, employmentId);
        } else {
            body = "{\"incomeType\":\"%s\",\"monthlyAmount\":%d}"
                    .formatted(incomeType, monthlyAmount);
        }
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // --- crown jewel: loan-level summary across two borrowers ---

    @Test
    void summaryAggregatesAllBorrowersAndMatchesDbSum() throws Exception {
        // Setup: one loan, two borrowers
        String loanId = createLoan();
        String borrowerAId = addBorrower(loanId, "Alice", "Smith", true);
        String borrowerBId = addBorrower(loanId, "Bob", "Jones", false);

        // Borrower A: employment + BASE 5000 + OVERTIME 500
        String empId = addEmployment(loanId, borrowerAId);
        addIncome(loanId, borrowerAId, "BASE", 5000, empId);
        addIncome(loanId, borrowerAId, "OVERTIME", 500, empId);

        // Borrower B: SOCIAL_SECURITY 1200 (no employer)
        addIncome(loanId, borrowerBId, "SOCIAL_SECURITY", 1200, null);

        // Call the summary endpoint
        MvcResult result = mvc.perform(get("/api/loans/{loanId}/income/summary", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rows.length()").value(3))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // totalMonthlyIncome == 6700 (tolerates 6700 vs 6700.00)
        Number totalRaw = JsonPath.read(body, "$.data.totalMonthlyIncome");
        BigDecimal total = new BigDecimal(totalRaw.toString());
        assertThat(total.compareTo(new BigDecimal("6700"))).isEqualTo(0);

        // BASE row for borrower A carries the employer name
        List<String> baseEmployerNames = JsonPath.read(body,
                "$.data.rows[?(@.incomeType=='BASE')].employerName");
        assertThat(baseEmployerNames).hasSize(1);
        assertThat(baseEmployerNames.get(0)).isEqualTo("ACME Corp");

        // SOCIAL_SECURITY row has a null employerName
        List<Object> ssEmployerNames = JsonPath.read(body,
                "$.data.rows[?(@.incomeType=='SOCIAL_SECURITY')].employerName");
        assertThat(ssEmployerNames).hasSize(1);
        assertThat(ssEmployerNames.get(0)).isNull();

        // Independent JDBC sum — proves the endpoint total equals DB truth for this loan
        BigDecimal dbSum = jdbc.queryForObject(
                "select coalesce(sum(monthly_amount), 0) from income_item where loan_id = ?::uuid",
                BigDecimal.class, loanId);
        assertThat(dbSum).isNotNull();
        assertThat(total.compareTo(dbSum)).isEqualTo(0);
    }

    // --- cross-org 404 ---

    @Test
    void crossOrgCannotSeeSummary404() throws Exception {
        String loanId = createLoan();

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(get("/api/loans/{loanId}/income/summary", loanId).with(otherOrg))
                .andExpect(status().isNotFound());
    }

    // --- no token 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{loanId}/income/summary", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
