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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssetSummaryIT extends AbstractIntegrationTest {

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

    private void addAsset(String loanId, String borrowerId, String assetType, String cashOrMarketValue) throws Exception {
        String body = cashOrMarketValue != null
                ? "{\"assetType\":\"%s\",\"cashOrMarketValue\":%s,\"financialInstitution\":\"Bank of Test\"}"
                        .formatted(assetType, cashOrMarketValue)
                : "{\"assetType\":\"%s\"}".formatted(assetType);
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/assets", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // --- crown jewel: loan-level summary across two borrowers ---

    @Test
    void summaryAggregatesAllBorrowersAndMatchesDbSum() throws Exception {
        String loanId = createLoan();
        String borrowerAId = addBorrower(loanId, "Alice", "Smith", true);
        String borrowerBId = addBorrower(loanId, "Bob", "Jones", false);

        // Borrower A: CHECKING 10000, SAVINGS 5000
        addAsset(loanId, borrowerAId, "CHECKING", "10000");
        addAsset(loanId, borrowerAId, "SAVINGS", "5000");
        // Borrower B: RETIREMENT 25000
        addAsset(loanId, borrowerBId, "RETIREMENT", "25000");

        MvcResult result = mvc.perform(get("/api/loans/{loanId}/assets/summary", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rows.length()").value(3))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        Number totalRaw = JsonPath.read(body, "$.data.totalAssets");
        BigDecimal total = new BigDecimal(totalRaw.toString());
        assertThat(total.compareTo(new BigDecimal("40000"))).isEqualTo(0);

        // Independent JDBC sum — proves endpoint total equals DB truth for this loan
        BigDecimal dbSum = jdbc.queryForObject(
                "select coalesce(sum(cash_or_market_value), 0) from asset where loan_id = ?::uuid",
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

        mvc.perform(get("/api/loans/{loanId}/assets/summary", loanId).with(otherOrg))
                .andExpect(status().isNotFound());
    }

    // --- no token 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{loanId}/assets/summary", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
