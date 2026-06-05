package com.msfg.los.tenancy;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CrossTenantIsolationIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static final String ORG_B = "00000000-0000-0000-0000-0000000000bb";
    static final String A_SUBJECT = UUID.randomUUID().toString();

    @BeforeEach
    void seedOrgB() {
        jdbc.update(
            "insert into organization (id,version,name,slug,status,settings) " +
            "values (?::uuid,0,'OrgB','org-b','ACTIVE','{}'::jsonb) on conflict (id) do nothing",
            ORG_B);
    }

    private RequestPostProcessor userA() {
        return jwt().jwt(j -> j.subject(A_SUBJECT).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private RequestPostProcessor userB() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", ORG_B))
                   .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    @Test
    void orgBcannotSeeOrgAloan() throws Exception {
        // Create a loan as userA (org DEFAULT_ORG)
        var res = mvc.perform(post("/api/loans").with(userA())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(A_SUBJECT)))
            .andExpect(status().isCreated())
            .andReturn();
        String aLoanId = com.jayway.jsonpath.JsonPath.read(
            res.getResponse().getContentAsString(), "$.data.id");

        // userA can see their own loan
        mvc.perform(get("/api/loans/{id}", aLoanId).with(userA()))
            .andExpect(status().isOk());

        // userB (different company) gets 404 — @TenantId filters the row at query time;
        // the loan is never found, so NotFoundException → 404 (not 403).
        mvc.perform(get("/api/loans/{id}", aLoanId).with(userB()))
            .andExpect(status().isNotFound());

        // B's pipeline does not contain A's loan
        mvc.perform(get("/api/loans").with(userB()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[?(@.id=='" + aLoanId + "')]").doesNotExist());
    }
}
