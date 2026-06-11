package com.msfg.los.fees.web;

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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InvoiceControllerIT extends AbstractIntegrationTest {

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

    // --- PUT with final:true → 200, $.data.final == true ---

    @Test
    void upsertInvoiceReturnsFinalTrue() throws Exception {
        String loanId = createLoan();

        mvc.perform(put("/api/loans/{l}/fees/invoices", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feeLabel\":\"Appraisal Fee\",\"final\":true," +
                                 "\"amountDisclosed\":500,\"invoiceAmount\":475," +
                                 "\"borrowerPoc\":0,\"comment\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feeLabel").value("Appraisal Fee"))
                .andExpect(jsonPath("$.data.final").value(true))
                .andExpect(jsonPath("$.data.amountDisclosed").value(500))
                .andExpect(jsonPath("$.data.invoiceAmount").value(475))
                .andExpect(jsonPath("$.data.borrowerPoc").value(0));
    }

    // --- GET → length 1, $.data[0].final == true, feeLabel correct ---

    @Test
    void listInvoicesAfterUpsert() throws Exception {
        String loanId = createLoan();

        // seed one
        mvc.perform(put("/api/loans/{l}/fees/invoices", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feeLabel\":\"Appraisal Fee\",\"final\":true," +
                                 "\"amountDisclosed\":500,\"invoiceAmount\":475," +
                                 "\"borrowerPoc\":0,\"comment\":\"x\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/loans/{l}/fees/invoices", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].feeLabel").value("Appraisal Fee"))
                .andExpect(jsonPath("$.data[0].final").value(true));
    }

    // --- 2nd PUT same feeLabel → DB count stays 1, values replaced, final now false ---

    @Test
    void upsertSameFeeLabelReplacesRowNotDuplicates() throws Exception {
        String loanId = createLoan();

        // first upsert: final=true
        mvc.perform(put("/api/loans/{l}/fees/invoices", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feeLabel\":\"Appraisal Fee\",\"final\":true," +
                                 "\"amountDisclosed\":500,\"invoiceAmount\":475," +
                                 "\"borrowerPoc\":0,\"comment\":\"x\"}"))
                .andExpect(status().isOk());

        // second upsert same feeLabel: final=false, different amounts
        mvc.perform(put("/api/loans/{l}/fees/invoices", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feeLabel\":\"Appraisal Fee\",\"final\":false," +
                                 "\"amountDisclosed\":600,\"invoiceAmount\":550," +
                                 "\"borrowerPoc\":25,\"comment\":\"updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.final").value(false))
                .andExpect(jsonPath("$.data.amountDisclosed").value(600));

        // DB count == 1 (upsert, not insert)
        int count = jdbc.queryForObject(
                "select count(*) from invoice_entry where loan_id=?::uuid and fee_label='Appraisal Fee'",
                Integer.class, loanId);
        assertThat(count).isEqualTo(1);

        // GET still shows 1 row with updated values
        mvc.perform(get("/api/loans/{l}/fees/invoices", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].final").value(false))
                .andExpect(jsonPath("$.data[0].amountDisclosed").value(600));
    }

    // --- cross-org → 404 ---

    @Test
    void crossOrgReturns404() throws Exception {
        String loanId = createLoan();

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(put("/api/loans/{l}/fees/invoices", loanId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feeLabel\":\"Appraisal Fee\",\"final\":false," +
                                 "\"amountDisclosed\":100,\"invoiceAmount\":100," +
                                 "\"borrowerPoc\":0}"))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/fees/invoices", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
