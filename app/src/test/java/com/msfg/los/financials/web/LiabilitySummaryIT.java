package com.msfg.los.financials.web;

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

class LiabilitySummaryIT extends AbstractIntegrationTest {

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

    private void addLiability(String loanId, String borrowerId, String liabilityType,
                              int monthlyPayment, int unpaidBalance,
                              boolean includeInDti, String exclusionReason) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("{\"liabilityType\":\"%s\",\"monthlyPayment\":%d,\"unpaidBalance\":%d,\"includeInDti\":%b"
                .formatted(liabilityType, monthlyPayment, unpaidBalance, includeInDti));
        if (exclusionReason != null) {
            body.append(",\"exclusionReason\":\"%s\"".formatted(exclusionReason));
        }
        body.append("}");

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/liabilities", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());
    }

    // --- crown jewel: all-vs-DTI totals, excluded liability in rows but not dtiMonthlyPayments ---

    @Test
    void summaryComputesAllVsDtiTotalsAndMatchesDbSums() throws Exception {
        String loanId = createLoan();
        String borrowerAId = addBorrower(loanId, "Alice", "Smith", true);
        String borrowerBId = addBorrower(loanId, "Bob", "Jones", false);

        // Borrower A: REVOLVING 500 (included), INSTALLMENT 300 (included)
        addLiability(loanId, borrowerAId, "REVOLVING", 500, 10000, true, null);
        addLiability(loanId, borrowerAId, "INSTALLMENT", 300, 5000, true, null);

        // Borrower B: MORTGAGE_LOAN 200 (EXCLUDED with reason) — present in rows, omitted from dtiMonthlyPayments
        addLiability(loanId, borrowerBId, "MORTGAGE_LOAN", 200, 80000, false, "PAID_AT_OR_BEFORE_CLOSING");

        MvcResult result = mvc.perform(get("/api/loans/{loanId}/liabilities/summary", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rows.length()").value(3))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // totalMonthlyPayments == Σ ALL (500+300+200 = 1000)
        Number totalRaw = JsonPath.read(body, "$.data.totalMonthlyPayments");
        BigDecimal totalMonthly = new BigDecimal(totalRaw.toString());
        assertThat(totalMonthly.compareTo(new BigDecimal("1000"))).isEqualTo(0);

        // dtiMonthlyPayments == Σ included only (500+300 = 800)
        Number dtiRaw = JsonPath.read(body, "$.data.dtiMonthlyPayments");
        BigDecimal dtiMonthly = new BigDecimal(dtiRaw.toString());
        assertThat(dtiMonthly.compareTo(new BigDecimal("800"))).isEqualTo(0);

        // Cross-check both totals against independent JDBC sums
        BigDecimal dbTotalMonthly = jdbc.queryForObject(
                "select coalesce(sum(monthly_payment), 0) from liability where loan_id = ?::uuid",
                BigDecimal.class, loanId);
        assertThat(dbTotalMonthly).isNotNull();
        assertThat(totalMonthly.compareTo(dbTotalMonthly)).isEqualTo(0);

        BigDecimal dbDtiMonthly = jdbc.queryForObject(
                "select coalesce(sum(monthly_payment), 0) from liability where loan_id = ?::uuid and include_in_dti = true",
                BigDecimal.class, loanId);
        assertThat(dbDtiMonthly).isNotNull();
        assertThat(dtiMonthly.compareTo(dbDtiMonthly)).isEqualTo(0);

        // The excluded liability IS present in rows with includeInDti=false
        List<Boolean> excludedFlags = JsonPath.read(body,
                "$.data.rows[?(@.liabilityType=='MORTGAGE_LOAN')].includeInDti");
        assertThat(excludedFlags).hasSize(1);
        assertThat(excludedFlags.get(0)).isFalse();

        // totalUnpaidBalance vs JDBC
        Number balanceRaw = JsonPath.read(body, "$.data.totalUnpaidBalance");
        BigDecimal totalBalance = new BigDecimal(balanceRaw.toString());
        BigDecimal dbBalance = jdbc.queryForObject(
                "select coalesce(sum(unpaid_balance), 0) from liability where loan_id = ?::uuid",
                BigDecimal.class, loanId);
        assertThat(dbBalance).isNotNull();
        assertThat(totalBalance.compareTo(dbBalance)).isEqualTo(0);
    }

    // --- cross-org 404 ---

    @Test
    void crossOrgCannotSeeSummary404() throws Exception {
        String loanId = createLoan();

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(get("/api/loans/{loanId}/liabilities/summary", loanId).with(otherOrg))
                .andExpect(status().isNotFound());
    }

    // --- no token 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{loanId}/liabilities/summary", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
