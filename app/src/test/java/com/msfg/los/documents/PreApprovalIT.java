package com.msfg.los.documents;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PreApprovalIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    /** Returns [loanId, loanNumber] */
    private String[] createLoanWithNumber() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        String loanId = com.jayway.jsonpath.JsonPath.read(body, "$.data.id");
        String loanNumber = com.jayway.jsonpath.JsonPath.read(body, "$.data.loanNumber");
        return new String[]{loanId, loanNumber};
    }

    private void addPrimaryBorrower(String loanId, String first, String last) throws Exception {
        mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"%s\",\"lastName\":\"%s\",\"primary\":true}".formatted(first, last)))
                .andExpect(status().isCreated());
    }

    // --- POST /pre-approval → 201 with correct metadata ---

    @Test
    void generatePreApprovalReturns201WithCorrectMetadata() throws Exception {
        var loanInfo = createLoanWithNumber();
        String loanId = loanInfo[0];
        addPrimaryBorrower(loanId, "Jane", "Smith");

        mvc.perform(post("/api/loans/{l}/documents/pre-approval", loanId).with(lo()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.documentType").value("PRE_APPROVAL"))
                .andExpect(jsonPath("$.data.contentType").value("text/html"))
                .andExpect(jsonPath("$.data.requestedBy").value(notNullValue()));
    }

    // --- GET ?type=PRE_APPROVAL lists the generated doc ---

    @Test
    void preApprovalAppearsInTypeFilteredList() throws Exception {
        var loanInfo = createLoanWithNumber();
        String loanId = loanInfo[0];
        addPrimaryBorrower(loanId, "Jane", "Smith");

        mvc.perform(post("/api/loans/{l}/documents/pre-approval", loanId).with(lo()))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/loans/{l}/documents", loanId).with(lo())
                        .param("type", "PRE_APPROVAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.items[0].documentType").value("PRE_APPROVAL"));
    }

    // --- download content contains loan number ---

    @Test
    void preApprovalContentContainsLoanNumber() throws Exception {
        var loanInfo = createLoanWithNumber();
        String loanId = loanInfo[0];
        String loanNumber = loanInfo[1];
        addPrimaryBorrower(loanId, "Jane", "Smith");

        var createRes = mvc.perform(post("/api/loans/{l}/documents/pre-approval", loanId).with(lo()))
                .andExpect(status().isCreated())
                .andReturn();
        String docId = com.jayway.jsonpath.JsonPath.read(createRes.getResponse().getContentAsString(), "$.data.id");

        var downloadRes = mvc.perform(get("/api/loans/{l}/documents/{d}/content", loanId, docId).with(lo()))
                .andExpect(status().isOk())
                .andReturn();

        String html = downloadRes.getResponse().getContentAsString();
        assertThat(html).contains(loanNumber);
    }

    // --- borrower-name markup is escaped in the letter (opus AUS-review M1 pin) ---

    @Test
    void preApprovalEscapesHtmlInBorrowerName() throws Exception {
        var loanInfo = createLoanWithNumber();
        String loanId = loanInfo[0];
        addPrimaryBorrower(loanId, "<script>alert(1)</script>", "Smith");

        var createRes = mvc.perform(post("/api/loans/{l}/documents/pre-approval", loanId).with(lo()))
                .andExpect(status().isCreated())
                .andReturn();
        String docId = com.jayway.jsonpath.JsonPath.read(createRes.getResponse().getContentAsString(), "$.data.id");

        var downloadRes = mvc.perform(get("/api/loans/{l}/documents/{d}/content", loanId, docId).with(lo()))
                .andExpect(status().isOk())
                .andReturn();

        String html = downloadRes.getResponse().getContentAsString();
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).doesNotContain("<script>alert(1)</script>");
    }

    // --- requestedBy == caller subject ---

    @Test
    void requestedByEqualsCallerSubject() throws Exception {
        var loanInfo = createLoanWithNumber();
        String loanId = loanInfo[0];
        addPrimaryBorrower(loanId, "Jane", "Smith");

        mvc.perform(post("/api/loans/{l}/documents/pre-approval", loanId).with(lo()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.requestedBy").value(LO));
    }

    // --- cross-org → 404 ---

    @Test
    void crossOrgReturns404() throws Exception {
        var loanInfo = createLoanWithNumber();
        String loanId = loanInfo[0];
        addPrimaryBorrower(loanId, "Jane", "Smith");

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(post("/api/loans/{l}/documents/pre-approval", loanId).with(otherOrg))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noTokenReturns401() throws Exception {
        mvc.perform(post("/api/loans/{l}/documents/pre-approval", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
