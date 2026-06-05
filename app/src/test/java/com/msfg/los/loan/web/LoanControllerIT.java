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
    void processorCannotCreate403() throws Exception {
        mvc.perform(post("/api/loans")
                .with(user(UUID.randomUUID().toString(), "ROLE_PROCESSOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
            .andExpect(status().isForbidden());
    }

    @Test
    void otherLoCannotAccessSomeoneElsesLoan403() throws Exception {
        String id = createLoan();   // owned by LO
        mvc.perform(get("/api/loans/{id}", id)
                .with(user(UUID.randomUUID().toString(), "ROLE_LO")))
            .andExpect(status().isForbidden());
    }
}
