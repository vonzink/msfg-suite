package com.msfg.los.loan.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.UUID;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LoanControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private RequestPostProcessor user(String sub, String role) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority(role));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
            .andExpect(status().isCreated())
            .andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    @Test
    void createReturnsNumberAndStarted() throws Exception {
        mvc.perform(post("/api/loans").with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\",\"mortgageType\":\"CONVENTIONAL\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.loanNumber").exists())
            .andExpect(jsonPath("$.data.status").value("STARTED"));
    }

    @Test
    void pipelineListsOwnLoan() throws Exception {
        createLoan();
        mvc.perform(get("/api/loans").with(lo()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void getByIdReturnsSummary() throws Exception {
        String id = createLoan();
        mvc.perform(get("/api/loans/{id}", id).with(lo()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void transitionUpdatesStatus() throws Exception {
        String id = createLoan();
        mvc.perform(post("/api/loans/{id}/status", id).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"APPLICATION_IN_PROGRESS\",\"reason\":\"go\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("APPLICATION_IN_PROGRESS"));
    }

    @Test
    void illegalTransitionIs409() throws Exception {
        String id = createLoan();
        mvc.perform(post("/api/loans/{id}/status", id).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"FUNDED\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans")).andExpect(status().isUnauthorized());
    }

    @Test
    void loanOfficerIdDefaultsToCallerWhenOmitted() throws Exception {
        mvc.perform(post("/api/loans").with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.loanOfficerId").value(LO));
    }

    @Test
    void explicitLoanOfficerIdIsUsedWhenProvided() throws Exception {
        String explicitId = UUID.randomUUID().toString();
        mvc.perform(post("/api/loans").with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(explicitId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.loanOfficerId").value(explicitId));
    }

    @Test
    void processorCannotCreate403() throws Exception {
        mvc.perform(post("/api/loans")
                .with(user(UUID.randomUUID().toString(), "ROLE_PROCESSOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
            .andExpect(status().isForbidden());
    }

    @Test
    void transitionsForStartedLoanAsLo() throws Exception {
        String id = createLoan();
        mvc.perform(get("/api/loans/{id}/status/transitions", id).with(lo()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentStatus").value("STARTED"))
            .andExpect(jsonPath("$.data.allowedTransitions", hasItems("APPLICATION_IN_PROGRESS", "WITHDRAWN", "CANCELLED")))
            .andExpect(jsonPath("$.data.allowedTransitions", not(hasItem("APPROVED_WITH_CONDITIONS"))));
    }

    @Test
    void transitionsRequiresToken() throws Exception {
        String id = createLoan();
        mvc.perform(get("/api/loans/{id}/status/transitions", id))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void transitionsCrossOrg404() throws Exception {
        String id = createLoan();
        mvc.perform(get("/api/loans/{id}/status/transitions", id)
                .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                      .claim("org_id", "ffffffff-ffff-ffff-ffff-ffffffffffff"))
                           .authorities(new SimpleGrantedAuthority("ROLE_LO"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void otherLoCannotAccessSomeoneElsesLoan403() throws Exception {
        String id = createLoan();   // owned by LO
        mvc.perform(get("/api/loans/{id}", id)
                .with(user(UUID.randomUUID().toString(), "ROLE_LO")))
            .andExpect(status().isForbidden());
    }

    /**
     * End-to-end: drive a loan to IN_UNDERWRITING, then assert that a ROLE_UNDERWRITER JWT
     * sees the gated targets (APPROVED_WITH_CONDITIONS, DENIED, SUSPENDED) in allowedTransitions.
     */
    @Test
    void underwriterSeesGatedTransitionsForInUnderwritingLoan() throws Exception {
        String id = createLoan(); // STARTED, owned by LO

        // STARTED -> APPLICATION_IN_PROGRESS (LO, ungated)
        mvc.perform(post("/api/loans/{id}/status", id).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"APPLICATION_IN_PROGRESS\",\"reason\":\"app\"}"))
            .andExpect(status().isOk());

        // APPLICATION_IN_PROGRESS -> SUBMITTED (LO, ungated)
        mvc.perform(post("/api/loans/{id}/status", id).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"SUBMITTED\",\"reason\":\"submit\"}"))
            .andExpect(status().isOk());

        // SUBMITTED -> IN_UNDERWRITING (LO, ungated)
        mvc.perform(post("/api/loans/{id}/status", id).with(lo())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"IN_UNDERWRITING\",\"reason\":\"uw\"}"))
            .andExpect(status().isOk());

        // Now query transitions AS an underwriter — a DIFFERENT org member, allowed in via
        // org-wide back-office access (2026-06-11 role-access spec).
        // The underwriter role causes lifecycle.allowedTransitions to include the gated targets.
        mvc.perform(get("/api/loans/{id}/status/transitions", id)
                .with(user(UUID.randomUUID().toString(), "ROLE_UNDERWRITER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentStatus").value("IN_UNDERWRITING"))
            .andExpect(jsonPath("$.data.allowedTransitions", hasItem("APPROVED_WITH_CONDITIONS")))
            .andExpect(jsonPath("$.data.allowedTransitions", hasItem("DENIED")))
            .andExpect(jsonPath("$.data.allowedTransitions", hasItem("SUSPENDED")));
    }
}
