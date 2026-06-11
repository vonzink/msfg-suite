package com.msfg.los.fees.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FeeControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

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

    private String addFee(String loanId, String section, String label, double amount) throws Exception {
        var body = "{\"section\":\"%s\",\"label\":\"%s\",\"amount\":%s}"
                .formatted(section, label, amount);
        var res = mvc.perform(post("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- add → 201 + ordinal 0 ---

    @Test
    void addFeeReturns201WithOrdinalZero() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Origination Fee\",\"amount\":1500}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.section").value("A"))
                .andExpect(jsonPath("$.data.label").value("Origination Fee"))
                .andExpect(jsonPath("$.data.amount").value(1500))
                .andExpect(jsonPath("$.data.ordinal").value(0));
    }

    // --- duplicate section+label → 409 ---

    @Test
    void duplicateSectionAndLabelReturns409() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Origination Fee\",\"amount\":1500}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Origination Fee\",\"amount\":2000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("A")));
    }

    // --- 2nd distinct fee gets ordinal 1 ---

    @Test
    void secondDistinctFeeGetsOrdinalOne() throws Exception {
        String loanId = createLoan();

        addFee(loanId, "A", "Origination Fee", 1500);

        mvc.perform(post("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"B\",\"label\":\"Appraisal Fee\",\"amount\":500}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ordinal").value(1));
    }

    // --- PATCH amount leaves section/label unchanged ---

    @Test
    void patchAmountLeavesSectionAndLabelUnchanged() throws Exception {
        String loanId = createLoan();
        String feeId = addFee(loanId, "A", "Origination Fee", 1500);

        mvc.perform(patch("/api/loans/{l}/fees/{f}", loanId, feeId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":2000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(2000))
                .andExpect(jsonPath("$.data.section").value("A"))
                .andExpect(jsonPath("$.data.label").value("Origination Fee"));
    }

    // --- negative amount → 400 with $.message ~ "amount" ---

    @Test
    void negativeAmountReturns400() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Bad Fee\",\"amount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("amount")));
    }

    // --- DELETE → 204 + list excludes it ---

    @Test
    void deleteFeeReturns204AndListExcludesIt() throws Exception {
        String loanId = createLoan();
        String feeId = addFee(loanId, "A", "Origination Fee", 1500);

        mvc.perform(delete("/api/loans/{l}/fees/{f}", loanId, feeId).with(lo()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/loans/{l}/fees", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- cross-org → 404 ---

    @Test
    void crossOrgReturns404() throws Exception {
        String loanId = createLoan();

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(post("/api/loans/{l}/fees", loanId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"A\",\"label\":\"Fee\",\"amount\":100}"))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/fees", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
