package com.msfg.los.disclosures.web;

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

/**
 * TRID coverage gate (12 CFR 1026.19) — GET /api/loans/{loanId}/disclosures/coverage.
 *
 * Loan model exposes NO positive out-of-scope signal (no HELOC / reverse / open-end /
 * business-purpose enum value on loanPurpose/mortgageType/amortizationType/lienPriority),
 * so a not-covered case cannot be constructed without fabricating enum values — the
 * notCoveredWhenOutOfScope test is intentionally omitted. v1 assumes covered.
 */
class CoverageIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor as(String sub, String role) {
        return as(sub, role, DEFAULT_ORG);
    }

    private RequestPostProcessor as(String sub, String role, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(LO, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    @Test
    void standardPurchaseLoanIsCovered() throws Exception {
        String id = createLoan();
        mvc.perform(get("/api/loans/{id}/disclosures/coverage", id).with(as(LO, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.covered").value(true))
                .andExpect(jsonPath("$.data.reasons").isArray())
                .andExpect(jsonPath("$.data.reasons.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void crossOrg404() throws Exception {
        String id = createLoan();   // lives in DEFAULT_ORG
        mvc.perform(get("/api/loans/{id}/disclosures/coverage", id)
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff")))
                .andExpect(status().isNotFound());
    }

    @Test
    void noToken401() throws Exception {
        String id = createLoan();
        mvc.perform(get("/api/loans/{id}/disclosures/coverage", id))
                .andExpect(status().isUnauthorized());
    }
}
