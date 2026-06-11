package com.msfg.los.fees.web;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FeeTotalsIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private void addFee(String loanId, String section, String label, double amount) throws Exception {
        mvc.perform(post("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"%s\",\"label\":\"%s\",\"amount\":%s}"
                                .formatted(section, label, amount)))
                .andExpect(status().isCreated());
    }

    // --- Crown jewel: totals with seeded fees ---

    @Test
    void totalsComputedCorrectlyWithJdbcVerification() throws Exception {
        String loanId = createLoan();

        // Seed: A=100, A=200 (two items), B=50, C=75, E=30, F=400, G=600
        addFee(loanId, "A", "Origination Fee 1", 100);
        addFee(loanId, "A", "Origination Fee 2", 200);
        addFee(loanId, "B", "Appraisal Fee", 50);
        addFee(loanId, "C", "Title Search", 75);
        addFee(loanId, "E", "Recording Fee", 30);
        addFee(loanId, "F", "Homeowner Insurance", 400);
        addFee(loanId, "G", "Prepaid Interest", 600);

        var res = mvc.perform(get("/api/loans/{l}/fees/totals", loanId).with(lo()))
                .andExpect(status().isOk())
                // sectionTotals
                .andExpect(jsonPath("$.data.sectionTotals.A").value(300))
                .andExpect(jsonPath("$.data.sectionTotals.F").value(400))
                .andExpect(jsonPath("$.data.sectionTotals.G").value(600))
                // categoryTotals
                .andExpect(jsonPath("$.data.categoryTotals.origination").value(300))
                .andExpect(jsonPath("$.data.categoryTotals.taxesGov").value(30))
                .andExpect(jsonPath("$.data.categoryTotals.escrowPrepaids").value(1000))
                .andReturn();

        // JDBC cross-check: escrowPrepaids == SUM(amount) where section in ('F','G')
        BigDecimal jdbcFG = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from fee_line_item where loan_id = ?::uuid and section in ('F','G')",
                BigDecimal.class, loanId);
        // JsonPath returns Double/Integer for JSON numbers — convert via toString for exact comparison
        Number escrowPrepaidsRaw = com.jayway.jsonpath.JsonPath.read(
                res.getResponse().getContentAsString(), "$.data.categoryTotals.escrowPrepaids");
        BigDecimal escrowPrepaids = new BigDecimal(escrowPrepaidsRaw.toString());
        assertEquals(0, escrowPrepaids.compareTo(jdbcFG),
                "escrowPrepaids should equal JDBC sum of F+G: expected " + jdbcFG + " got " + escrowPrepaids);

        // JDBC cross-check: sectionTotals.A == SUM(amount) where section = 'A'
        BigDecimal jdbcA = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from fee_line_item where loan_id = ?::uuid and section = 'A'",
                BigDecimal.class, loanId);
        Number sectionARaw = com.jayway.jsonpath.JsonPath.read(
                res.getResponse().getContentAsString(), "$.data.sectionTotals.A");
        BigDecimal sectionA = new BigDecimal(sectionARaw.toString());
        assertEquals(0, sectionA.compareTo(jdbcA),
                "sectionTotals.A should equal JDBC sum for A: expected " + jdbcA + " got " + sectionA);
    }

    // --- /totals does NOT clash with /{feeId} ---

    @Test
    void totalsEndpointNotTreatedAsFeeId() throws Exception {
        String loanId = createLoan();

        // GET /fees/totals must return 200 (not 404 as if "totals" were a UUID feeId)
        mvc.perform(get("/api/loans/{l}/fees/totals", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sectionTotals").exists())
                .andExpect(jsonPath("$.data.categoryTotals").exists());
    }

    // --- Empty loan: all totals 0 (no 500) ---

    @Test
    void emptyLoanAllTotalsZero() throws Exception {
        String loanId = createLoan();

        mvc.perform(get("/api/loans/{l}/fees/totals", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sectionTotals.A").value(0))
                .andExpect(jsonPath("$.data.sectionTotals.B").value(0))
                .andExpect(jsonPath("$.data.sectionTotals.F").value(0))
                .andExpect(jsonPath("$.data.sectionTotals.G").value(0))
                .andExpect(jsonPath("$.data.categoryTotals.origination").value(0))
                .andExpect(jsonPath("$.data.categoryTotals.escrowPrepaids").value(0));
    }

    // --- cross-org → 404 on totals ---

    @Test
    void crossOrgTotalsReturns404() throws Exception {
        String loanId = createLoan();

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(get("/api/loans/{l}/fees/totals", loanId).with(otherOrg))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/fees/totals", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
