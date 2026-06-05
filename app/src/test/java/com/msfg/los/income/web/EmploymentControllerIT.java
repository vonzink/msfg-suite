package com.msfg.los.income.web;

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

class EmploymentControllerIT extends AbstractIntegrationTest {

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

    private String addEmployment(String loanId, String borrowerId, String body) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- happy path ---

    @Test
    void addCurrentEmploymentThenList() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerName\":\"ACME Corp\",\"positionTitle\":\"Engineer\"," +
                                 "\"employmentStatus\":\"CURRENT\",\"startDate\":\"2020-01-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.employerName").value("ACME Corp"))
                .andExpect(jsonPath("$.data.positionTitle").value("Engineer"))
                .andExpect(jsonPath("$.data.ordinal").value(0));
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void addSelfEmployedRequiresOwnershipShare() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        // selfEmployed=true without ownershipShare → 400
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selfEmployed\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownershipShareRequiresSelfEmployed() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        // ownershipShare without selfEmployed → 400
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerName\":\"ACME\",\"ownershipShare\":\"LESS_THAN_25\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void employerNameRequiredUnlessSelf() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        // no employerName, selfEmployed not set → 400
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionTitle\":\"Analyst\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void previousEndDateBeforeStartDateRejected() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        // PREVIOUS with endDate before startDate → 400
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerName\":\"Old Co\",\"employmentStatus\":\"PREVIOUS\"," +
                                 "\"startDate\":\"2019-06-01\",\"endDate\":\"2019-01-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchLeavesOtherFieldsUnchanged() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        String empId = addEmployment(loanId, borrowerId,
                "{\"employerName\":\"StartCo\",\"positionTitle\":\"Dev\",\"startDate\":\"2018-03-01\"}");
        mvc.perform(patch("/api/loans/{l}/borrowers/{b}/employments/{e}", loanId, borrowerId, empId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionTitle\":\"Senior Dev\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.positionTitle").value("Senior Dev"))
                .andExpect(jsonPath("$.data.employerName").value("StartCo")); // unchanged
    }

    @Test
    void deleteRemovesEmployment() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        String empId = addEmployment(loanId, borrowerId,
                "{\"employerName\":\"ToDelete Inc\",\"startDate\":\"2021-01-01\"}");
        mvc.perform(delete("/api/loans/{l}/borrowers/{b}/employments/{e}", loanId, borrowerId, empId).with(lo()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void otherOrgCannotAddEmployment404() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerName\":\"X\"}"))
                .andExpect(status().isNotFound());   // loan filtered out by tenant scope
    }

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/employments", UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void previousEmploymentRequiresEndDate() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        // PREVIOUS with startDate but no endDate → 400
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerName\":\"Old Co\",\"employmentStatus\":\"PREVIOUS\"," +
                                 "\"startDate\":\"2018-01-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void selfEmployedWithOwnershipShareReturns201() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        // selfEmployed=true + ownershipShare → 201, response echoes selfEmployed=true
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selfEmployed\":true,\"ownershipShare\":\"GREATER_OR_EQUAL_25\"," +
                                 "\"startDate\":\"2015-06-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.selfEmployed").value(true));
    }
}
