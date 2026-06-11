package com.msfg.los.coc;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CocSubmitIT extends AbstractIntegrationTest {

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

    // --- submit with no reason → 400 VALIDATION_ERROR with fields.reason ---

    @Test
    void submitNoReasonReturns400WithFieldsReason() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/coc/submit", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"structureChanges\":[],\"feeChanges\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.reason", notNullValue()));
    }

    // --- submit with reason → 201 PENDING with submittedBy ---

    @Test
    void submitWithReasonReturns201Pending() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/coc/submit", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"BORROWER_REQUESTED\",\"structureChanges\":[],\"feeChanges\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.submittedBy").value(LO));
    }

    // --- GET history → length 1 after submit ---

    @Test
    void historyHasOneEntryAfterSubmit() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/coc/submit", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"BORROWER_REQUESTED\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/loans/{l}/coc/history", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    // --- GET draft after submit → empty (draft cleared) ---

    @Test
    void draftClearedAfterSubmit() throws Exception {
        String loanId = createLoan();

        // first save a draft
        mvc.perform(put("/api/loans/{l}/coc/draft", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"CLERICAL\",\"feeChanges\":[{\"section\":\"A\",\"label\":\"Fee\",\"currentValue\":100,\"requestedValue\":150,\"reason\":\"x\",\"hasInvoice\":\"No\"}]}"))
                .andExpect(status().isOk());

        // submit
        mvc.perform(post("/api/loans/{l}/coc/submit", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"CLERICAL\"}"))
                .andExpect(status().isCreated());

        // draft should be empty
        mvc.perform(get("/api/loans/{l}/coc/draft", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feeChanges", hasSize(0)))
                .andExpect(jsonPath("$.data.structureChanges", hasSize(0)));
    }

    // --- cross-org → 404 ---

    @Test
    void crossOrgReturns404() throws Exception {
        String loanId = createLoan();

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(post("/api/loans/{l}/coc/submit", loanId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noTokenReturns401() throws Exception {
        mvc.perform(post("/api/loans/{l}/coc/submit", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isUnauthorized());
    }
}
