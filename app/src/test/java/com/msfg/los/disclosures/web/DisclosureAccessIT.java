package com.msfg.los.disclosures.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role / negative coverage sweep for the disclosures endpoints (Disclosures Task 12).
 *
 * Characterizes the EXISTING access model + validation seam (disclosures uses {@code assertCanAccess}
 * via the shared {@code LoanAccessGuard} + the same {@code SecurityConfig}/platform exception handlers
 * as every other module): PLATFORM_ADMIN is pinned OUT of loan data, back-office PROCESSOR is org-wide,
 * a foreign LO is owner-scoped out, malformed enum bodies are platform 400s, and a fee-less loan still
 * issues an LE without a 500. Mirrors {@link CoverageIT}/{@link LoanEstimateIT} jwt-as helpers.
 */
class DisclosureAccessIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    private RequestPostProcessor as(String sub, String role) {
        return as(sub, role, DEFAULT_ORG);
    }

    private RequestPostProcessor as(String sub, String role, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org))
                .authorities(new SimpleGrantedAuthority(role));
    }

    /** Purchase loan owned by {@code loSub}, with the §4 figures the LE assembles from. */
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

    /**
     * Access-model pin: PLATFORM_ADMIN administers orgs, NOT loan files. Platform operators have no
     * loan-data access, so a loan-scoped disclosures read must be 403 (not 200) — defends against the
     * tenant operator silently reading every lender's borrower disclosures.
     */
    @Test
    void platformAdminForbiddenOnCoverage() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(get("/api/loans/{id}/disclosures/coverage", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());
    }

    /**
     * Back-office roles (PROCESSOR/UNDERWRITER/CLOSER) are org-wide: a PROCESSOR who is NOT the loan's
     * LO can still read coverage AND issue an LE on any loan in the same org.
     */
    @Test
    void processorOrgWideCanReadAndIssue() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addFee(lo, loanId);

        String processor = UUID.randomUUID().toString();   // not the loan's LO
        mvc.perform(get("/api/loans/{id}/disclosures/coverage", loanId)
                        .with(as(processor, "ROLE_PROCESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.covered").value(true));

        mvc.perform(post("/api/loans/{id}/disclosures/loan-estimate", loanId)
                        .with(as(processor, "ROLE_PROCESSOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("LOAN_ESTIMATE"));
    }

    /** A DIFFERENT LO (owner-scoped, same org) cannot touch the first LO's loan disclosures → 403. */
    @Test
    void otherLoForbidden() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        String otherLo = UUID.randomUUID().toString();
        mvc.perform(get("/api/loans/{id}/disclosures/coverage", loanId)
                        .with(as(otherLo, "ROLE_LO")))
                .andExpect(status().isForbidden());
    }

    /**
     * Malformed enum in the body — {@code deliveryMethod:"CARRIER_PIGEON"} is not a {@code DeliveryMethod}
     * — fails Jackson deserialization → the platform HttpMessageNotReadable handler → 400 VALIDATION_ERROR
     * (not a 500).
     */
    @Test
    void badEnumBody400() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(post("/api/loans/{id}/disclosures/loan-estimate", loanId)
                        .with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryMethod\":\"CARRIER_PIGEON\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /**
     * A loan with NO fees still issues an LE (empty cost table, figures present-or-null) — must be a
     * clean 201 with an APR and a status, never a 500 from an empty fee assembly.
     */
    @Test
    void emptyFeesLoanStillIssues() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);   // deliberately NO addFee

        mvc.perform(post("/api/loans/{id}/disclosures/loan-estimate", loanId)
                        .with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", notNullValue()))
                .andExpect(jsonPath("$.data.apr", notNullValue()));
    }
}
