package com.msfg.los.aus.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Credit orders (AUS Task 8): port-backed tri-merge ordering — per-borrower-per-bureau scores,
 * the vendor report stored as a real loan document, REISSUE reusing the bureau-assigned
 * creditReportIdentifier on a NEW row with its OWN artifact.
 */
class CreditOrderIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private RequestPostProcessor as(String sub, String role) {
        return as(sub, role, DEFAULT_ORG);
    }

    private RequestPostProcessor as(String sub, String role, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org))
                .authorities(new SimpleGrantedAuthority(role));
    }

    /** Creates a loan owned by the given LO subject; returns the loan id. */
    private String createLoan(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Adds a borrower via the real parties endpoint; returns the borrower id. */
    private String createBorrower(String loSub, String loanId, String first, String last,
                                  boolean primary) throws Exception {
        var res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"%s\",\"lastName\":\"%s\",\"primary\":%s}"
                                .formatted(first, last, primary)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /**
     * CROWN JEWEL: joint tri-merge order returns 201 with a bureau-style identifier, 6 scores
     * (2 borrowers x 3 bureaus), a COMPLETE status, and a stored report document — cross-checked
     * straight in the database and downloaded through the real documents endpoint.
     */
    @Test
    void orderJointTriMergeProducesScoresAndReport() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String b1 = createBorrower(lo, loanId, "Alice", "Anderson", true);
        String b2 = createBorrower(lo, loanId, "Bob", "Barker", false);

        var res = mvc.perform(post("/api/loans/{loanId}/credit/order", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SUBMIT\",\"requestType\":\"JOINT\",\"borrowerIds\":[\"%s\",\"%s\"]}"
                                .formatted(b1, b2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.creditReportIdentifier", matchesPattern("XS-\\d{8}")))
                .andExpect(jsonPath("$.data.scores.length()").value(6))
                .andExpect(jsonPath("$.data.status").value("COMPLETE"))
                .andExpect(jsonPath("$.data.equifax").value(true))
                .andExpect(jsonPath("$.data.experian").value(true))
                .andExpect(jsonPath("$.data.transUnion").value(true))
                .andExpect(jsonPath("$.data.reportDocumentId", notNullValue()))
                .andExpect(jsonPath("$.data.requestedBy").value(lo))
                .andExpect(jsonPath("$.data.requestedAt", notNullValue()))
                .andReturn();
        String reportDocumentId = JsonPath.read(res.getResponse().getContentAsString(),
                "$.data.reportDocumentId");

        // Crown-jewel cross-checks straight from the database.
        Integer storedScores = jdbc.queryForObject(
                "select jsonb_array_length(scores) from credit_order where loan_id = ?::uuid",
                Integer.class, loanId);
        assertThat(storedScores).isEqualTo(6);
        Integer documentRows = jdbc.queryForObject(
                "select count(*) from document where id = ?::uuid", Integer.class, reportDocumentId);
        assertThat(documentRows).isEqualTo(1);

        // The artifact is a real loan document: downloadable, and it names borrower 1.
        mvc.perform(get("/api/loans/{loanId}/documents/{docId}/content", loanId, reportDocumentId)
                        .with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alice")));
    }

    /** REISSUE travels with the bureau-assigned identifier but lands a NEW row + NEW artifact. */
    @Test
    void reissueReusesIdentifierNewRow() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String b1 = createBorrower(lo, loanId, "Cora", "Chase", true);
        String b2 = createBorrower(lo, loanId, "Dan", "Drake", false);

        var first = mvc.perform(post("/api/loans/{loanId}/credit/order", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SUBMIT\",\"requestType\":\"JOINT\",\"borrowerIds\":[\"%s\",\"%s\"]}"
                                .formatted(b1, b2)))
                .andExpect(status().isCreated()).andReturn();
        String firstBody = first.getResponse().getContentAsString();
        String firstId = JsonPath.read(firstBody, "$.data.id");
        String identifier = JsonPath.read(firstBody, "$.data.creditReportIdentifier");
        String firstDocId = JsonPath.read(firstBody, "$.data.reportDocumentId");

        var second = mvc.perform(post("/api/loans/{loanId}/credit/order", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"action\":\"REISSUE\",\"requestType\":\"JOINT\",\"borrowerIds\":[\"%s\",\"%s\"],"
                                + "\"creditReportIdentifier\":\"%s\"}").formatted(b1, b2, identifier)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.creditReportIdentifier").value(identifier))
                .andExpect(jsonPath("$.data.action").value("REISSUE"))
                .andReturn();
        String secondBody = second.getResponse().getContentAsString();
        String secondId = JsonPath.read(secondBody, "$.data.id");
        String secondDocId = JsonPath.read(secondBody, "$.data.reportDocumentId");

        assertThat(secondId).isNotEqualTo(firstId);
        // Each row owns its artifact.
        assertThat(secondDocId).isNotEqualTo(firstDocId);
        Integer rows = jdbc.queryForObject(
                "select count(*) from credit_order where loan_id = ?::uuid", Integer.class, loanId);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void reissueWithoutIdentifier400() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String b1 = createBorrower(lo, loanId, "Eve", "Ellis", true);

        mvc.perform(post("/api/loans/{loanId}/credit/order", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REISSUE\",\"requestType\":\"INDIVIDUAL\",\"borrowerIds\":[\"%s\"]}"
                                .formatted(b1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("creditReportIdentifier")));
    }

    @Test
    void unknownBorrower400() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String b1 = createBorrower(lo, loanId, "Finn", "Frost", true);

        mvc.perform(post("/api/loans/{loanId}/credit/order", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SUBMIT\",\"requestType\":\"JOINT\",\"borrowerIds\":[\"%s\",\"%s\"]}"
                                .formatted(b1, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("borrower")));
    }

    @Test
    void emptyBorrowers400() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(post("/api/loans/{loanId}/credit/order", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SUBMIT\",\"requestType\":\"JOINT\",\"borrowerIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("borrowerIds")));

        // Null borrowerIds is the same failure.
        mvc.perform(post("/api/loans/{loanId}/credit/order", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SUBMIT\",\"requestType\":\"JOINT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("borrowerIds")));
    }

    @Test
    void historyNewestFirst() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String b1 = createBorrower(lo, loanId, "Gail", "Gray", true);

        var first = mvc.perform(post("/api/loans/{loanId}/credit/order", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SUBMIT\",\"requestType\":\"INDIVIDUAL\",\"borrowerIds\":[\"%s\"]}"
                                .formatted(b1)))
                .andExpect(status().isCreated()).andReturn();
        String firstBody = first.getResponse().getContentAsString();
        String firstId = JsonPath.read(firstBody, "$.data.id");
        String identifier = JsonPath.read(firstBody, "$.data.creditReportIdentifier");

        var second = mvc.perform(post("/api/loans/{loanId}/credit/order", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"action\":\"REISSUE\",\"requestType\":\"INDIVIDUAL\",\"borrowerIds\":[\"%s\"],"
                                + "\"creditReportIdentifier\":\"%s\"}").formatted(b1, identifier)))
                .andExpect(status().isCreated()).andReturn();
        String reissueId = JsonPath.read(second.getResponse().getContentAsString(), "$.data.id");

        mvc.perform(get("/api/loans/{loanId}/credit/orders", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(reissueId))
                .andExpect(jsonPath("$.data[1].id").value(firstId));
    }

    @Test
    void crossOrg404() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String b1 = createBorrower(lo, loanId, "Hank", "Hale", true);

        mvc.perform(post("/api/loans/{loanId}/credit/order", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SUBMIT\",\"requestType\":\"INDIVIDUAL\",\"borrowerIds\":[\"%s\"]}"
                                .formatted(b1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{loanId}/credit/orders", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
