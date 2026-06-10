package com.msfg.los.loan.web;

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
import static org.hamcrest.Matchers.*;

class LoanPipelineEnrichmentIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO_A = UUID.randomUUID().toString();
    static final String LO_B = UUID.randomUUID().toString();

    private RequestPostProcessor lo(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** Creates a loan for LO_A and returns its id. */
    private String createLoan(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo(loSub))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
            .andExpect(status().isCreated())
            .andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    @Test
    void pipelineEnriches_primaryBorrowerName_propertyCity_updatedAt() throws Exception {
        String loanId = createLoan(LO_A);

        // PATCH subject-property city + state
        mvc.perform(patch("/api/loans/{id}", loanId).with(lo(LO_A))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"city\":\"Denver\",\"state\":\"CO\"}"))
            .andExpect(status().isOk());

        // Add a primary borrower
        mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo(LO_A))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName":"Abbas","lastName":"Hussein","primary":true}
                    """))
            .andExpect(status().isCreated());

        // GET pipeline — find the row for this loan
        String resp = mvc.perform(get("/api/loans").with(lo(LO_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items", hasSize(greaterThanOrEqualTo(1))))
            .andReturn().getResponse().getContentAsString();

        // locate index of our loan in the items array
        com.jayway.jsonpath.DocumentContext doc = com.jayway.jsonpath.JsonPath.parse(resp);
        java.util.List<String> ids = doc.read("$.data.items[*].id");
        int idx = ids.indexOf(loanId);
        org.assertj.core.api.Assertions.assertThat(idx).as("loan must appear in pipeline").isGreaterThanOrEqualTo(0);

        mvc.perform(get("/api/loans").with(lo(LO_A)))
            .andExpect(jsonPath("$.data.items[%d].primaryBorrowerName".formatted(idx)).value("Abbas Hussein"))
            .andExpect(jsonPath("$.data.items[%d].propertyCity".formatted(idx)).value("Denver"))
            .andExpect(jsonPath("$.data.items[%d].propertyState".formatted(idx)).value("CO"))
            .andExpect(jsonPath("$.data.items[%d].updatedAt".formatted(idx)).isNotEmpty());
    }

    @Test
    void pipelineItem_nullBorrowerName_whenNoBorrower() throws Exception {
        String loanId = createLoan(LO_B);

        String resp = mvc.perform(get("/api/loans").with(lo(LO_B)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        com.jayway.jsonpath.DocumentContext doc = com.jayway.jsonpath.JsonPath.parse(resp);
        java.util.List<String> ids = doc.read("$.data.items[*].id");
        int idx = ids.indexOf(loanId);
        org.assertj.core.api.Assertions.assertThat(idx).as("loan must appear in pipeline").isGreaterThanOrEqualTo(0);

        // primaryBorrowerName must be null (JSON null or absent)
        Object name = doc.read("$.data.items[%d].primaryBorrowerName".formatted(idx));
        org.assertj.core.api.Assertions.assertThat(name).isNull();
    }
}
