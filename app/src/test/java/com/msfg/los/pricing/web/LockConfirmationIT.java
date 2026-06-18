package com.msfg.los.pricing.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LockConfirmationIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG).claim("email", "lo@msfg.test"))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    void patchLoan(String loanId, String jsonBody) throws Exception {
        mvc.perform(patch("/api/loans/{id}", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk());
    }

    /** Standard priceable loan: total 300000, ltv 80.000 (basis = min(375k sales, 380k appraised)), fico 745. */
    String priceableLoan() throws Exception {
        String id = createLoan();
        patchLoan(id, """
                {
                  "baseLoanAmount": 300000,
                  "interestRate": 6.5,
                  "loanTermMonths": 360,
                  "qualifyingCreditScore": 745,
                  "salesPrice": 375000,
                  "appraisedValue": 380000,
                  "amortizationType": "FIXED"
                }
                """);
        return id;
    }

    String cypBody() {
        return """
                {"rate": 6.5, "commitmentDays": 30, "compensationPayerType": "LENDER_PAID"}
                """;
    }

    @Test
    void generateLockConfirmation_storesListableDownloadableHtml() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());

        var res = mvc.perform(post("/api/loans/{id}/pricing/lock-confirmation", loanId).with(lo()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.documentType").value("LOCK_CONFIRMATION"))
                .andExpect(jsonPath("$.data.contentType").value("text/html"))
                .andReturn();
        String docId = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");

        // Phase-1 list shape: {count, documents}; generated docs land UPLOADED so they list.
        mvc.perform(get("/api/loans/{id}/documents", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents[?(@.id == '" + docId + "')]",
                        org.hamcrest.Matchers.hasSize(1)));

        var download = mvc.perform(get("/api/loans/{id}/documents/{docId}/content", loanId, docId).with(lo()))
                .andExpect(status().isOk()).andReturn();
        String html = download.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html).contains("6.500").contains("Lock Confirmation");
    }

    @Test
    void generateLockConfirmation_unlocked_409() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock-confirmation", loanId).with(lo()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_STATE_CONFLICT"));
    }
}
