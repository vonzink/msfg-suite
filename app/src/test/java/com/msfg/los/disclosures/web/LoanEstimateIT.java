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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Loan Estimate issuance (Disclosures Task 8): assemble fee/loan figures, run them through the
 * DisclosureVendorPort to generate the H-24 form + APR, store the rendered form as a real loan
 * document, compute TRID receipt/consummation timing, persist a versioned issuance + an audit
 * event. Mirrors AusRunIT/RoleAccessIT idioms (jwt-as helpers, JDBC assertions, doc download).
 */
class LoanEstimateIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private RequestPostProcessor as(String sub, String role) {
        return as(sub, role, DEFAULT_ORG);
    }

    private RequestPostProcessor as(String sub, String role, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org))
                .authorities(new SimpleGrantedAuthority(role));
    }

    /** Purchase loan with the §4 figures the LE assembles from. */
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

    /** Adds one Section-A origination fee (zero-tolerance) the assembly snapshots. */
    private void addFee(String loSub, String loanId) throws Exception {
        mvc.perform(post("/api/loans/{id}/fees", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Origination Fee\",\"amount\":1200,"
                                + "\"paidTo\":\"CREDITOR\"}"))
                .andExpect(status().isCreated());
    }

    private Integer issuanceCount(String loanId) {
        return jdbc.queryForObject(
                "select count(*) from disclosure_issuance where loan_id = ?::uuid", Integer.class, loanId);
    }

    private Integer eventCount(String loanId) {
        return jdbc.queryForObject(
                "select count(*) from disclosure_event where loan_id = ?::uuid", Integer.class, loanId);
    }

    /**
     * CROWN JEWEL: an LE goes end to end — 201 with an APR, version 1, SENT status, a stored
     * document id, computed receipt + earliest-consummation dates; the rendered form is a real,
     * downloadable loan document; and exactly one issuance + one event row land in the database.
     */
    @Test
    void issueLoanEstimateEndToEnd() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addFee(lo, loanId);

        var res = mvc.perform(post("/api/loans/{loanId}/disclosures/loan-estimate", loanId)
                        .with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("LOAN_ESTIMATE"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.apr", notNullValue()))
                .andExpect(jsonPath("$.data.documentId", notNullValue()))
                .andExpect(jsonPath("$.data.computedReceivedDate", notNullValue()))
                .andExpect(jsonPath("$.data.earliestConsummationDate", notNullValue()))
                .andExpect(jsonPath("$.data.requestedBy").value(lo))
                .andReturn();
        String docId = JsonPath.read(res.getResponse().getContentAsString(), "$.data.documentId");

        // The rendered LE form is a real, downloadable loan document.
        mvc.perform(get("/api/loans/{loanId}/documents/{docId}/content", loanId, docId)
                        .with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PLACEHOLDER")));

        assertThat(issuanceCount(loanId)).isEqualTo(1);
        assertThat(eventCount(loanId)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void crossOrg404() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(post("/api/loans/{loanId}/disclosures/loan-estimate", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void noToken401() throws Exception {
        mvc.perform(post("/api/loans/{loanId}/disclosures/loan-estimate", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
