package com.msfg.los.income.web;

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

class IncomeControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addBorrower(String loanId) throws Exception {
        var res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"primary\":true}"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Add a W2 employment (CURRENT, with employerName) and return its id. */
    private String addEmployment(String loanId, String borrowerId) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerName\":\"ACME Corp\",\"employmentStatus\":\"CURRENT\"," +
                                 "\"startDate\":\"2020-01-01\"}"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Add a self-employed employment and return its id. */
    private String addSelfEmployment(String loanId, String borrowerId) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selfEmployed\":true,\"ownershipShare\":\"GREATER_OR_EQUAL_25\"," +
                                 "\"startDate\":\"2015-01-01\"}"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- happy path ---

    @Test
    void addBaseIncomeThenList() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        String empId = addEmployment(loanId, borrowerId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"BASE\",\"monthlyAmount\":5000,\"employmentId\":\"%s\"}".formatted(empId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.incomeType").value("BASE"))
                .andExpect(jsonPath("$.data.monthlyAmount").value(5000))
                .andExpect(jsonPath("$.data.ordinal").value(0));

        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // --- employment-type validation: employmentId required ---

    @Test
    void baseIncomeWithNullEmploymentIdReturns400() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"BASE\",\"monthlyAmount\":5000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("employmentId")));
    }

    // --- other-source validation: employmentId must be null ---

    @Test
    void socialSecurityWithEmploymentIdReturns400() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        String empId = addEmployment(loanId, borrowerId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"SOCIAL_SECURITY\",\"monthlyAmount\":1200," +
                                 "\"employmentId\":\"%s\"}".formatted(empId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("employmentId")));
    }

    @Test
    void socialSecurityWithNullEmploymentIdReturns201() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"SOCIAL_SECURITY\",\"monthlyAmount\":1200}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.incomeType").value("SOCIAL_SECURITY"));
    }

    // --- cross-borrower employmentId rejected ---

    @Test
    void baseIncomeWithOtherBorrowersEmploymentIdReturns400() throws Exception {
        String loanId = createLoan();
        String borrower1Id = addBorrower(loanId);
        // add a second borrower
        var res2 = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"John\",\"lastName\":\"Smith\",\"primary\":false}"))
                .andExpect(status().isCreated()).andReturn();
        String borrower2Id = com.jayway.jsonpath.JsonPath.read(
                res2.getResponse().getContentAsString(), "$.data.id");

        // create employment under borrower2
        String borrower2EmpId = addEmployment(loanId, borrower2Id);

        // try to add BASE income to borrower1 referencing borrower2's employment
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrower1Id).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"BASE\",\"monthlyAmount\":3000," +
                                 "\"employmentId\":\"%s\"}".formatted(borrower2EmpId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("employmentId")));
    }

    // --- sign rules ---

    @Test
    void selfEmploymentIncomeNegativeAmountReturns201() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        String seEmpId = addSelfEmployment(loanId, borrowerId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"SELF_EMPLOYMENT_INCOME\",\"monthlyAmount\":-500," +
                                 "\"employmentId\":\"%s\"}".formatted(seEmpId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.monthlyAmount").value(-500));
    }

    @Test
    void baseIncomeNegativeAmountReturns400() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        String empId = addEmployment(loanId, borrowerId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"BASE\",\"monthlyAmount\":-100," +
                                 "\"employmentId\":\"%s\"}".formatted(empId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("monthlyAmount")));
    }

    // --- cascade: delete employment cascades income ---

    @Test
    void deleteEmploymentCascadesIncomeItems() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        String empId = addEmployment(loanId, borrowerId);

        // add BASE income linked to that employment
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"BASE\",\"monthlyAmount\":4000," +
                                 "\"employmentId\":\"%s\"}".formatted(empId)))
                .andExpect(status().isCreated());

        // delete the employment
        mvc.perform(delete("/api/loans/{l}/borrowers/{b}/employments/{e}", loanId, borrowerId, empId).with(lo()))
                .andExpect(status().isNoContent());

        // fresh GET: income row must be gone (ON DELETE CASCADE)
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- ordinal check with multiple items ---

    @Test
    void secondIncomeItemGetsOrdinalOne() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        String empId = addEmployment(loanId, borrowerId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"BASE\",\"monthlyAmount\":5000," +
                                 "\"employmentId\":\"%s\"}".formatted(empId)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"OVERTIME\",\"monthlyAmount\":500," +
                                 "\"employmentId\":\"%s\"}".formatted(empId)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].ordinal").value(1));
    }

    // --- cross-org 404 ---

    @Test
    void otherOrgCannotAddIncome404() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"SOCIAL_SECURITY\",\"monthlyAmount\":1000}"))
                .andExpect(status().isNotFound());
    }

    // --- no token 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
