package com.msfg.los.conditions.web;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConditionControllerIT extends AbstractIntegrationTest {

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

    private RequestPostProcessor lo() {
        return as(LO_A, "ROLE_LO");
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO_A)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addCondition(String loanId, String text) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/conditions", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionText\":\"%s\"}".formatted(text)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- create → 201, defaults to Outstanding, echoes fields ---

    @Test
    void createReturns201WithOutstandingDefault() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/conditions", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionText\":\"Provide 2 most recent paystubs\"," +
                                "\"conditionType\":\"PriorToDocs\",\"assignedTo\":\"borrower\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(notNullValue()))
                .andExpect(jsonPath("$.data.loanId").value(loanId))
                .andExpect(jsonPath("$.data.conditionText").value("Provide 2 most recent paystubs"))
                .andExpect(jsonPath("$.data.conditionType").value("PriorToDocs"))
                .andExpect(jsonPath("$.data.status").value("Outstanding"))
                .andExpect(jsonPath("$.data.assignedTo").value("borrower"))
                .andExpect(jsonPath("$.data.clearedAt").value(nullValue()));
    }

    // --- list ordered oldest-first, with count ---

    @Test
    void listOrderedWithCount() throws Exception {
        String loanId = createLoan();

        addCondition(loanId, "First condition");
        addCondition(loanId, "Second condition");

        mvc.perform(get("/api/loans/{l}/conditions", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.conditions.length()").value(2))
                .andExpect(jsonPath("$.data.conditions[0].conditionText").value("First condition"))
                .andExpect(jsonPath("$.data.conditions[1].conditionText").value("Second condition"));
    }

    // --- conditionText blank → 400 ---

    @Test
    void blankConditionText400() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/conditions", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionText\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("conditionText must not be blank")));
    }

    // --- PATCH Outstanding → Cleared stamps cleared_at/by ---

    @Test
    void patchClearStampsClearedAtAndBy() throws Exception {
        String loanId = createLoan();
        String conditionId = addCondition(loanId, "Verify employment");

        mvc.perform(patch("/api/loans/{l}/conditions/{c}", loanId, conditionId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Cleared\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("Cleared"))
                .andExpect(jsonPath("$.data.clearedAt").value(notNullValue()))
                .andExpect(jsonPath("$.data.clearedBy").value(LO_A));
    }

    // --- PATCH Waived also stamps; reopen → Outstanding wipes the stamp ---

    @Test
    void patchWaiveThenReopenWipesStamp() throws Exception {
        String loanId = createLoan();
        String conditionId = addCondition(loanId, "Title commitment");

        mvc.perform(patch("/api/loans/{l}/conditions/{c}", loanId, conditionId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Waived\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("Waived"))
                .andExpect(jsonPath("$.data.clearedAt").value(notNullValue()))
                .andExpect(jsonPath("$.data.clearedBy").value(LO_A));

        mvc.perform(patch("/api/loans/{l}/conditions/{c}", loanId, conditionId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Outstanding\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("Outstanding"))
                .andExpect(jsonPath("$.data.clearedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.clearedBy").value(nullValue()));
    }

    // --- PATCH partial leaves other fields unchanged ---

    @Test
    void patchPartialLeavesOthers() throws Exception {
        String loanId = createLoan();
        var res = mvc.perform(post("/api/loans/{l}/conditions", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionText\":\"Appraisal review\",\"conditionType\":\"AtClosing\"}"))
                .andExpect(status().isCreated()).andReturn();
        String conditionId = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");

        mvc.perform(patch("/api/loans/{l}/conditions/{c}", loanId, conditionId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"ordered 6/18\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes").value("ordered 6/18"))
                .andExpect(jsonPath("$.data.conditionText").value("Appraisal review"))
                .andExpect(jsonPath("$.data.conditionType").value("AtClosing"))
                .andExpect(jsonPath("$.data.status").value("Outstanding"));
    }

    // --- PATCH provided-but-blank conditionText → 400 ---

    @Test
    void patchBlankConditionText400() throws Exception {
        String loanId = createLoan();
        String conditionId = addCondition(loanId, "Original text");

        mvc.perform(patch("/api/loans/{l}/conditions/{c}", loanId, conditionId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionText\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("conditionText must not be blank")));
    }

    // --- cross-loan condition id → 404 ---

    @Test
    void crossLoanSameOrg404() throws Exception {
        String loanA = createLoan();
        String loanB = createLoan();
        String condOnA = addCondition(loanA, "Belongs to A");

        mvc.perform(patch("/api/loans/{l}/conditions/{c}", loanB, condOnA).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Cleared\"}"))
                .andExpect(status().isNotFound());
    }

    // --- soft-delete → 204, then absent from the list ---

    @Test
    void softDeleteThenAbsent() throws Exception {
        String loanId = createLoan();
        String keep = addCondition(loanId, "Keep me");
        String drop = addCondition(loanId, "Drop me");

        mvc.perform(delete("/api/loans/{l}/conditions/{c}", loanId, drop).with(lo()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/loans/{l}/conditions", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.conditions.length()").value(1))
                .andExpect(jsonPath("$.data.conditions[0].id").value(keep));

        // a second delete of the now-soft-deleted row → 404 (no longer loadable)
        mvc.perform(delete("/api/loans/{l}/conditions/{c}", loanId, drop).with(lo()))
                .andExpect(status().isNotFound());
    }

    // --- cross-org JWT → 404 (tenant isolation) ---

    @Test
    void crossOrg404() throws Exception {
        String loanId = createLoan();
        addCondition(loanId, "Insider condition");

        mvc.perform(get("/api/loans/{l}/conditions", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff")))
                .andExpect(status().isNotFound());
    }

    // --- PROCESSOR sees another LO's conditions org-wide → 200 ---

    @Test
    void processorOrgWide200() throws Exception {
        String loanId = createLoan();
        addCondition(loanId, "Org wide visible");

        mvc.perform(get("/api/loans/{l}/conditions", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
    }

    // --- no token → 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/conditions", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
