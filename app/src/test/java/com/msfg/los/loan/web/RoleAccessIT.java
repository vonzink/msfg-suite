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

/** Back-office org-wide access (spec 2026-06-11): PROCESSOR/UNDERWRITER/CLOSER vs LO/PLATFORM_ADMIN. */
class RoleAccessIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO_A = UUID.randomUUID().toString();

    private RequestPostProcessor as(String sub, String role) {
        return as(sub, role, DEFAULT_ORG);
    }

    private RequestPostProcessor as(String sub, String role, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private String createLoanOwnedByLoA() throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO_A)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Pages through GET /api/loans as `who` and reports whether loanId appears anywhere. */
    private boolean pipelineContains(RequestPostProcessor who, String loanId) throws Exception {
        int page = 0;
        while (true) {
            String body = mvc.perform(get("/api/loans?size=100&page=" + page).with(who))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            com.jayway.jsonpath.DocumentContext doc = com.jayway.jsonpath.JsonPath.parse(body);
            java.util.List<String> ids = doc.read("$.data.items[*].id");
            if (ids.contains(loanId)) return true;
            int totalPages = doc.read("$.data.totalPages", Integer.class);
            if (++page >= totalPages) return false;
        }
    }

    // --- ops roles can open another LO's loan ---

    @Test
    void processorCanReadAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void underwriterCanReadAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_UNDERWRITER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void closerCanReadAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_CLOSER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }

    // --- ops pipeline is org-wide ---

    @Test
    void processorPipelineListsOtherLosLoans() throws Exception {
        String id = createLoanOwnedByLoA();
        org.assertj.core.api.Assertions.assertThat(
                pipelineContains(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR"), id)).isTrue();
    }

    // --- LO stays scoped ---

    @Test
    void loPipelineDoesNotListOtherLosLoans() throws Exception {
        String id = createLoanOwnedByLoA();
        org.assertj.core.api.Assertions.assertThat(
                pipelineContains(as(UUID.randomUUID().toString(), "ROLE_LO"), id)).isFalse();
    }

    // --- PLATFORM_ADMIN pinned out of loan data ---

    @Test
    void platformAdminCannotReadLoans403() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());
    }

    // --- role breadth never crosses the tenant wall ---

    @Test
    void processorCrossOrgStill404() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff")))
                .andExpect(status().isNotFound());
    }

    @Test
    void processorPipelineNeverListsOtherOrgsLoans() throws Exception {
        String id = createLoanOwnedByLoA();   // lives in DEFAULT_ORG
        boolean visible = pipelineContains(
                as(UUID.randomUUID().toString(), "ROLE_PROCESSOR", "ffffffff-ffff-ffff-ffff-ffffffffffff"), id);
        org.assertj.core.api.Assertions.assertThat(visible).isFalse();
    }
}
