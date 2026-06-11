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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PUT /api/loans/{loanId}/fees — upsert keyed by (section,label), mirroring the
 * frontend's Record key `${sectionId}:${label}`. Create-or-update, idempotent.
 */
class FeeUpsertIT extends AbstractIntegrationTest {

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

    private long countRows(String loanId, String section, String label) {
        Long n = jdbc.queryForObject(
                "select count(*) from fee_line_item where loan_id = ?::uuid and section = ? and label = ?",
                Long.class, loanId, section, label);
        return n == null ? -1 : n;
    }

    // --- create when absent ---

    @Test
    void upsertCreatesRowWhenAbsent() throws Exception {
        String loanId = createLoan();

        mvc.perform(put("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Origination Fee\",\"amount\":1500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.section").value("A"))
                .andExpect(jsonPath("$.data.label").value("Origination Fee"))
                .andExpect(jsonPath("$.data.amount").value(1500))
                .andExpect(jsonPath("$.data.ordinal").value(0));

        assertEquals(1, countRows(loanId, "A", "Origination Fee"));
    }

    // --- update in place: same id, same ordinal, single row ---

    @Test
    void upsertUpdatesSameRowKeepingIdAndOrdinal() throws Exception {
        String loanId = createLoan();

        var first = mvc.perform(put("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Origination Fee\",\"amount\":1500}"))
                .andExpect(status().isOk()).andReturn();
        String id1 = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.data.id");

        mvc.perform(put("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Origination Fee\",\"amount\":2000,\"sellerConcession\":250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id1))
                .andExpect(jsonPath("$.data.amount").value(2000))
                .andExpect(jsonPath("$.data.sellerConcession").value(250))
                .andExpect(jsonPath("$.data.ordinal").value(0));

        assertEquals(1, countRows(loanId, "A", "Origination Fee"));
    }

    // --- credits: negative amount + sellerConcession accepted; totals reflect it ---

    @Test
    void upsertAcceptsNegativeAmountsForCredits() throws Exception {
        String loanId = createLoan();

        mvc.perform(put("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"PRORATIONS\",\"label\":\"County Tax Proration\",\"amount\":-150,\"sellerConcession\":-25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(-150))
                .andExpect(jsonPath("$.data.sellerConcession").value(-25));

        var res = mvc.perform(get("/api/loans/{l}/fees/totals", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sectionTotals.PRORATIONS").value(-150))
                .andReturn();

        // independent JDBC cross-check of the credit in the section sum
        BigDecimal jdbcSum = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from fee_line_item where loan_id = ?::uuid and section = 'PRORATIONS'",
                BigDecimal.class, loanId);
        Number raw = com.jayway.jsonpath.JsonPath.read(
                res.getResponse().getContentAsString(), "$.data.sectionTotals.PRORATIONS");
        assertEquals(0, new BigDecimal(raw.toString()).compareTo(jdbcSum),
                "PRORATIONS total should equal JDBC sum: expected " + jdbcSum);
    }

    // --- percent stays >= 0 ---

    @Test
    void upsertNegativePercentReturns400() throws Exception {
        String loanId = createLoan();

        mvc.perform(put("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Origination Fee\",\"amount\":100,\"percent\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("percent")));
    }

    // --- blank label rejected ---

    @Test
    void upsertBlankLabelReturns400() throws Exception {
        String loanId = createLoan();

        mvc.perform(put("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"\",\"amount\":100}"))
                .andExpect(status().isBadRequest());
    }

    // --- cross-org → 404 ---

    @Test
    void upsertCrossOrgReturns404() throws Exception {
        String loanId = createLoan();

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(put("/api/loans/{l}/fees", loanId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Fee\",\"amount\":100}"))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void upsertNoToken401() throws Exception {
        mvc.perform(put("/api/loans/{l}/fees", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Fee\",\"amount\":100}"))
                .andExpect(status().isUnauthorized());
    }
}
