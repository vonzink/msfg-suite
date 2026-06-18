package com.msfg.los.dashboard.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dashboard aggregator IT (Phase 2 T6): GET assembles every section from its owning service; PATCH
 * terms reuses the loan update path; tenant isolation + loan-access gating.
 */
class DashboardIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO_A = UUID.randomUUID().toString();
    static final String OTHER_ORG = "00000000-0000-0000-0000-0000000000bb";

    private RequestPostProcessor as(String sub, String role, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private RequestPostProcessor lo() {
        return as(LO_A, "ROLE_LO", DEFAULT_ORG);
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO_A)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Fully-populated loan: borrower (primary) + property + condition + note + a status transition. */
    private String seededLoan() throws Exception {
        String loanId = createLoan();

        // primary borrower
        mvc.perform(post("/api/loans/{l}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"primary\":true," +
                                "\"email\":\"ada@example.com\",\"cellPhone\":\"555-0100\"," +
                                "\"ssn\":\"123-45-6789\",\"maritalStatus\":\"MARRIED\"}"))
                .andExpect(status().isCreated());

        // property + terms via PATCH /api/loans/{id}
        mvc.perform(patch("/api/loans/{id}", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Austin\",\"state\":\"TX\",\"addressLine1\":\"1 Main St\"," +
                                "\"estimatedValue\":400000,\"baseLoanAmount\":320000,\"interestRate\":6.5," +
                                "\"loanTermMonths\":360,\"proposedTaxesMonthly\":300}"))
                .andExpect(status().isOk());

        // a section-L purchase credit
        mvc.perform(post("/api/loans/{l}/fees", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"L\",\"label\":\"Earnest Money\",\"amount\":-5000}"))
                .andExpect(status().isCreated());

        // a title-company contact
        mvc.perform(post("/api/loans/{l}/contacts", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TITLE_COMPANY\",\"name\":\"Jane Title\",\"company\":\"Acme Title Co\"}"))
                .andExpect(status().isCreated());

        // a condition (outstanding)
        mvc.perform(post("/api/loans/{l}/conditions", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionText\":\"Provide 2 paystubs\"}"))
                .andExpect(status().isCreated());

        // a note
        mvc.perform(post("/api/loans/{l}/notes", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Called borrower; docs coming\"}"))
                .andExpect(status().isCreated());

        // a status transition (STARTED -> APPLICATION_IN_PROGRESS), no special role needed
        mvc.perform(post("/api/loans/{id}/status", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"APPLICATION_IN_PROGRESS\",\"reason\":\"intake complete\"}"))
                .andExpect(status().isOk());

        return loanId;
    }

    // --- GET assembles every section from the right source -------------------

    @Test
    void getDashboardAssemblesAllSections() throws Exception {
        String loanId = seededLoan();

        mvc.perform(get("/api/loans/{l}/dashboard", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loanId").value(loanId))
                .andExpect(jsonPath("$.data.applicationNumber").value(notNullValue()))
                .andExpect(jsonPath("$.data.status").value("APPLICATION_IN_PROGRESS"))
                .andExpect(jsonPath("$.data.identifiers.loanNumber").value(notNullValue()))
                // primary borrower from parties, SSN never present
                .andExpect(jsonPath("$.data.primaryBorrower.firstName").value("Ada"))
                .andExpect(jsonPath("$.data.primaryBorrower.lastName").value("Lovelace"))
                .andExpect(jsonPath("$.data.primaryBorrower.email").value("ada@example.com"))
                .andExpect(jsonPath("$.data.primaryBorrower.phone").value("555-0100"))
                .andExpect(jsonPath("$.data.primaryBorrower.maritalStatus").value("MARRIED"))
                .andExpect(jsonPath("$.data.primaryBorrower.ssn").doesNotExist())
                .andExpect(jsonPath("$.data.primaryBorrower.ssnLast4").doesNotExist())
                // property
                .andExpect(jsonPath("$.data.property.city").value("Austin"))
                .andExpect(jsonPath("$.data.property.state").value("TX"))
                .andExpect(jsonPath("$.data.property.estimatedValue").value(400000))
                // loan terms (§4)
                .andExpect(jsonPath("$.data.loanTerms.baseLoanAmount").value(320000))
                .andExpect(jsonPath("$.data.loanTerms.interestRate").value(6.5))
                .andExpect(jsonPath("$.data.loanTerms.loanTermMonths").value(360))
                // housing expenses
                .andExpect(jsonPath("$.data.housingExpenses.proposedTaxesMonthly").value(300))
                // purchase credits (fees section L)
                .andExpect(jsonPath("$.data.purchaseCredits[0].label").value("Earnest Money"))
                .andExpect(jsonPath("$.data.purchaseCredits[0].amount").value(-5000))
                // conditions: the condition appears + outstanding count = 1
                .andExpect(jsonPath("$.data.conditions.outstandingCount").value(1))
                .andExpect(jsonPath("$.data.conditions.items[0].conditionText").value("Provide 2 paystubs"))
                // status history present (newest-first: the AIP transition is first)
                .andExpect(jsonPath("$.data.statusHistory[0].toStatus").value("APPLICATION_IN_PROGRESS"))
                .andExpect(jsonPath("$.data.statusHistory[0].note").value("intake complete"))
                // loan agents (contacts)
                .andExpect(jsonPath("$.data.loanAgents[0].role").value("TITLE_COMPANY"))
                // closing info assembled from the title contact
                .andExpect(jsonPath("$.data.closingInformation.titleCompany").value("Acme Title Co"))
                // notes newest-first
                .andExpect(jsonPath("$.data.notes[0].content").value("Called borrower; docs coming"));
    }

    // --- PATCH terms reuses the loan update path -----------------------------

    @Test
    void patchTermsUpdatesAndEchoes() throws Exception {
        String loanId = seededLoan();

        mvc.perform(patch("/api/loans/{l}/dashboard/terms", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseLoanAmount\":350000,\"interestRate\":6.875}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseLoanAmount").value(350000))
                .andExpect(jsonPath("$.data.interestRate").value(6.875));

        // persisted: reflected back through the dashboard GET
        mvc.perform(get("/api/loans/{l}/dashboard", loanId).with(lo()))
                .andExpect(jsonPath("$.data.loanTerms.baseLoanAmount").value(350000));
    }

    @Test
    void patchTermsRejectsInsaneInterestRate() throws Exception {
        String loanId = seededLoan();

        mvc.perform(patch("/api/loans/{l}/dashboard/terms", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interestRate\":150}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("interestRate")));
    }

    // --- tenant isolation: a cross-org JWT cannot see the loan → 404 ---------

    @Test
    void crossOrgDashboard404() throws Exception {
        String loanId = seededLoan();

        mvc.perform(get("/api/loans/{l}/dashboard", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO", OTHER_ORG)))
                .andExpect(status().isNotFound());
    }

    // --- loan-access: a different LO in the same org (not the owner) → 403 ---

    @Test
    void nonOwnerLoForbidden() throws Exception {
        String loanId = seededLoan();

        mvc.perform(get("/api/loans/{l}/dashboard", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO", DEFAULT_ORG)))
                .andExpect(status().isForbidden());
    }
}
