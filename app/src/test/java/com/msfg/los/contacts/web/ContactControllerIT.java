package com.msfg.los.contacts.web;

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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContactControllerIT extends AbstractIntegrationTest {

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

    private String addContact(String loanId, String role, String name) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/contacts", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"%s\",\"name\":\"%s\"}".formatted(role, name)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- create → 201, echoes all 5 fields, ordinal 0 ---

    @Test
    void createReturns201WithOrdinalZero() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/contacts", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"LISTING_AGENT\",\"name\":\"Jane Realtor\"," +
                                "\"company\":\"Acme Realty\",\"phone\":\"555-0100\",\"email\":\"jane@acme.test\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(notNullValue()))
                .andExpect(jsonPath("$.data.role").value("LISTING_AGENT"))
                .andExpect(jsonPath("$.data.name").value("Jane Realtor"))
                .andExpect(jsonPath("$.data.company").value("Acme Realty"))
                .andExpect(jsonPath("$.data.phone").value("555-0100"))
                .andExpect(jsonPath("$.data.email").value("jane@acme.test"))
                .andExpect(jsonPath("$.data.ordinal").value(0));
    }

    // --- list ordered by ordinal ---

    @Test
    void listOrderedByOrdinal() throws Exception {
        String loanId = createLoan();

        addContact(loanId, "LISTING_AGENT", "First Agent");
        addContact(loanId, "ESCROW_OFFICER", "Second Officer");

        mvc.perform(get("/api/loans/{l}/contacts", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].ordinal").value(0))
                .andExpect(jsonPath("$.data[0].name").value("First Agent"))
                .andExpect(jsonPath("$.data[1].ordinal").value(1))
                .andExpect(jsonPath("$.data[1].name").value("Second Officer"));
    }

    // --- PATCH subset leaves other fields unchanged ---

    @Test
    void patchSubsetLeavesOthers() throws Exception {
        String loanId = createLoan();

        var res = mvc.perform(post("/api/loans/{l}/contacts", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"APPRAISER\",\"name\":\"Val U. Ation\",\"company\":\"FastAppraise\"}"))
                .andExpect(status().isCreated()).andReturn();
        String contactId = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");

        mvc.perform(patch("/api/loans/{l}/contacts/{c}", loanId, contactId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"555-0199\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("555-0199"))
                .andExpect(jsonPath("$.data.role").value("APPRAISER"))
                .andExpect(jsonPath("$.data.name").value("Val U. Ation"))
                .andExpect(jsonPath("$.data.company").value("FastAppraise"));
    }

    // --- PATCH name provided-but-blank → 400 ---

    @Test
    void patchBlankNameReturns400() throws Exception {
        String loanId = createLoan();
        String contactId = addContact(loanId, "ATTORNEY", "Sue Yu");

        mvc.perform(patch("/api/loans/{l}/contacts/{c}", loanId, contactId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("name must not be blank")));
    }

    // --- delete → 204, next add gets max+1, never a reused count ---

    @Test
    void deleteThenOrdinalNotReused() throws Exception {
        String loanId = createLoan();

        String first = addContact(loanId, "LISTING_AGENT", "First");   // ordinal 0
        addContact(loanId, "SELLING_AGENT", "Second");                 // ordinal 1

        mvc.perform(delete("/api/loans/{l}/contacts/{c}", loanId, first).with(lo()))
                .andExpect(status().isNoContent());

        // survivor holds ordinal 1; count would reassign 1 (collision) — must be max+1 = 2
        mvc.perform(post("/api/loans/{l}/contacts", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TITLE_COMPANY\",\"name\":\"Third\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ordinal").value(2));

        mvc.perform(get("/api/loans/{l}/contacts", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].ordinal").value(1))
                .andExpect(jsonPath("$.data[1].ordinal").value(2));
    }

    // --- missing role → 400 with fields.role ---

    @Test
    void missingRole400() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/contacts", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No Role\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.role", notNullValue()));
    }

    // --- missing name → 400 with fields.name ---

    @Test
    void missingName400() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/contacts", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"INSURANCE_AGENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name", notNullValue()));
    }

    // --- cross-org JWT → 404 ---

    @Test
    void crossOrg404() throws Exception {
        String loanId = createLoan();
        addContact(loanId, "OTHER", "Insider");

        mvc.perform(get("/api/loans/{l}/contacts", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff")))
                .andExpect(status().isNotFound());
    }

    // --- same org, wrong loan path → 404 ---

    @Test
    void crossLoanSameOrg404() throws Exception {
        String loanA = createLoan();
        String loanB = createLoan();
        String contactOnA = addContact(loanA, "ESCROW_OFFICER", "Esc Row");

        mvc.perform(patch("/api/loans/{l}/contacts/{c}", loanB, contactOnA).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"555-0123\"}"))
                .andExpect(status().isNotFound());
    }

    // --- PLATFORM_ADMIN pinned out of loan data → 403 ---

    @Test
    void platformAdmin403() throws Exception {
        String loanId = createLoan();

        mvc.perform(get("/api/loans/{l}/contacts", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());
    }

    // --- PROCESSOR sees another LO's contacts org-wide → 200 ---

    @Test
    void processorOrgWide200() throws Exception {
        String loanId = createLoan();
        addContact(loanId, "SELLING_AGENT", "Org Wide");

        mvc.perform(get("/api/loans/{l}/contacts", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // --- no token → 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/contacts", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
