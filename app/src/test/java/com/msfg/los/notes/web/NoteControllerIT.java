package com.msfg.los.notes.web;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NoteControllerIT extends AbstractIntegrationTest {

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

    /** An LO whose principal carries a display-name claim (for author-name stamping assertions). */
    private RequestPostProcessor loNamed() {
        return jwt().jwt(j -> j.subject(LO_A).claim("org_id", DEFAULT_ORG).claim("name", "Dana Officer"))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
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

    private String addNote(String loanId, String content) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/notes", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"%s\"}".formatted(content)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- create → 201, stamps author id + name from the principal ---

    @Test
    void createStampsAuthorFromPrincipal() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/notes", loanId).with(loNamed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Called borrower, awaiting paystubs\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(notNullValue()))
                .andExpect(jsonPath("$.data.loanId").value(loanId))
                .andExpect(jsonPath("$.data.content").value("Called borrower, awaiting paystubs"))
                .andExpect(jsonPath("$.data.authorId").value(LO_A))
                .andExpect(jsonPath("$.data.authorName").value("Dana Officer"))
                .andExpect(jsonPath("$.data.createdAt").value(notNullValue()));
    }

    // --- list newest-first, with count ---

    @Test
    void listNewestFirstWithCount() throws Exception {
        String loanId = createLoan();

        addNote(loanId, "First note");
        addNote(loanId, "Second note");
        addNote(loanId, "Third note");

        mvc.perform(get("/api/loans/{l}/notes", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(3))
                .andExpect(jsonPath("$.data.notes.length()").value(3))
                .andExpect(jsonPath("$.data.notes[0].content").value("Third note"))
                .andExpect(jsonPath("$.data.notes[2].content").value("First note"));
    }

    // --- blank content → 400 ---

    @Test
    void blankContent400() throws Exception {
        String loanId = createLoan();

        // @NotBlank fires first → bean-validation 400 with the field named under $.fields.
        mvc.perform(post("/api/loans/{l}/notes", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.content").value(notNullValue()));
    }

    // --- cross-loan note id → 404 ---

    @Test
    void crossLoanSameOrg404() throws Exception {
        String loanA = createLoan();
        String loanB = createLoan();
        String noteOnA = addNote(loanA, "Belongs to A");

        mvc.perform(delete("/api/loans/{l}/notes/{n}", loanB, noteOnA).with(lo()))
                .andExpect(status().isNotFound());
    }

    // --- delete → 204, then absent + a second delete → 404 ---

    @Test
    void deleteThenAbsent() throws Exception {
        String loanId = createLoan();
        String keep = addNote(loanId, "Keep me");
        String drop = addNote(loanId, "Drop me");

        mvc.perform(delete("/api/loans/{l}/notes/{n}", loanId, drop).with(lo()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/loans/{l}/notes", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.notes.length()").value(1))
                .andExpect(jsonPath("$.data.notes[0].id").value(keep));

        // a second hard-delete of the now-gone row → 404
        mvc.perform(delete("/api/loans/{l}/notes/{n}", loanId, drop).with(lo()))
                .andExpect(status().isNotFound());
    }

    // --- PROCESSOR sees another LO's notes org-wide → 200 ---

    @Test
    void processorOrgWide200() throws Exception {
        String loanId = createLoan();
        addNote(loanId, "Org wide visible");

        mvc.perform(get("/api/loans/{l}/notes", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
    }

    // --- cross-org JWT → 404 (tenant isolation) ---

    @Test
    void crossOrg404() throws Exception {
        String loanId = createLoan();
        addNote(loanId, "Insider note");

        mvc.perform(get("/api/loans/{l}/notes", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff")))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/notes", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
