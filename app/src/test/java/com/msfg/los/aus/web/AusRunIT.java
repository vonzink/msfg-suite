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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUS runs (AUS Task 9): credential-gated DU/LPA submission through the AusVendorPort —
 * reissue/order credit wiring, ONE_CLICK fan-out (DU then LPA), findings stored as real loan
 * documents, casefile-id reuse on resubmit, newest-first history.
 */
class AusRunIT extends AbstractIntegrationTest {

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

    private RequestPostProcessor admin() {
        return as(UUID.randomUUID().toString(), "ROLE_ADMIN");
    }

    /**
     * Creates a loan owned by the given LO subject and patches the §4 fields the AUS file is
     * built from: note 300000 against a 400000 value = 75% LTV, under the stub's 97% cap.
     */
    private String createLoan(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
                .andExpect(status().isCreated()).andReturn();
        String loanId = JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
        mvc.perform(patch("/api/loans/{id}", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"noteAmount":300000,"estimatedValue":400000,"appraisedValue":400000,
                                 "interestRate":6.5,"loanTermMonths":360}"""))
                .andExpect(status().isOk());
        return loanId;
    }

    /** Adds a borrower via the real parties endpoint; returns the borrower id. */
    private String addBorrower(String loSub, String loanId) throws Exception {
        var res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"primary\":true}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Org-level credentials for one vendor — a username/password pair suffices for the stub. */
    private void putOrgCreds(String vendor) throws Exception {
        mvc.perform(put("/api/org/vendor-credentials/{vendor}", vendor).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u-1\",\"password\":\"p-1\"}"))
                .andExpect(status().isOk());
    }

    /**
     * Tests share one DB; other classes seed org-level creds for DEFAULT_ORG. Tests asserting a
     * cred-free state for a vendor start from a clean slate (mirrors AusProfileIT's idiom).
     */
    private void deleteOrgCredentials(String vendor) {
        jdbc.update("delete from vendor_credential where loan_id is null and org_id = ?::uuid and vendor = ?",
                DEFAULT_ORG, vendor);
    }

    private void putReissueProfile(String loSub, String loanId, String vendorKey, String borrowerId,
                                   String reference) throws Exception {
        mvc.perform(put("/api/loans/{loanId}/aus/profile", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"%s":{"issueMode":"REISSUE",
                                 "creditReferences":[{"borrowerId":"%s","reference":"%s"}]}}"""
                                .formatted(vendorKey, borrowerId, reference)))
                .andExpect(status().isOk());
    }

    private Integer ausRunCount(String loanId) {
        return jdbc.queryForObject("select count(*) from aus_run where loan_id = ?::uuid",
                Integer.class, loanId);
    }

    /**
     * CROWN JEWEL: a DU run with reissue refs goes end to end — 201 with a DU casefile id,
     * Approve/Eligible at 75% LTV, the reissued credit reference carried onto the run, findings
     * stored as REAL loan documents (HTML downloaded through the documents endpoint names the
     * recommendation AND the casefile id), and exactly one aus_run row in the database.
     */
    @Test
    void duRunWithReissueRefsEndToEnd() throws Exception {
        putOrgCreds("DU");
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String borrowerId = addBorrower(lo, loanId);
        putReissueProfile(lo, loanId, "du", borrowerId, "ABC123");

        var res = mvc.perform(post("/api/loans/{loanId}/aus/run", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"DU\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].vendor").value("DU"))
                .andExpect(jsonPath("$.data[0].vendorCaseId", matchesPattern("DU-\\d{10}")))
                .andExpect(jsonPath("$.data[0].recommendation").value("APPROVE_ELIGIBLE"))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETE"))
                .andExpect(jsonPath("$.data[0].creditReportIdentifier").value("ABC123"))
                .andExpect(jsonPath("$.data[0].findingsHtmlDocumentId", notNullValue()))
                .andExpect(jsonPath("$.data[0].findingsXmlDocumentId", notNullValue()))
                .andExpect(jsonPath("$.data[0].requestedBy").value(lo))
                .andExpect(jsonPath("$.data[0].requestedAt", notNullValue()))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        String caseId = JsonPath.read(body, "$.data[0].vendorCaseId");
        String htmlDocId = JsonPath.read(body, "$.data[0].findingsHtmlDocumentId");

        // The findings HTML is a real, downloadable loan document naming the decision + casefile.
        mvc.perform(get("/api/loans/{loanId}/documents/{docId}/content", loanId, htmlDocId)
                        .with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Approve/Eligible")))
                .andExpect(content().string(containsString(caseId)));

        assertThat(ausRunCount(loanId)).isEqualTo(1);
    }

    /** ONE_CLICK fans out to both vendors in DU,LPA order; history returns them newest-first. */
    @Test
    void oneClickRunsBothVendors() throws Exception {
        putOrgCreds("DU");
        putOrgCreds("LPA");
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String borrowerId = addBorrower(lo, loanId);
        putReissueProfile(lo, loanId, "du", borrowerId, "ABC123");
        putReissueProfile(lo, loanId, "lpa", borrowerId, "ABC123");

        mvc.perform(post("/api/loans/{loanId}/aus/run", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"ONE_CLICK\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].vendor").value("DU"))
                .andExpect(jsonPath("$.data[0].recommendation").value("APPROVE_ELIGIBLE"))
                .andExpect(jsonPath("$.data[1].vendor").value("LPA"))
                .andExpect(jsonPath("$.data[1].recommendation").value("ACCEPT"))
                .andExpect(jsonPath("$.data[1].vendorCaseId", matchesPattern("LPA-\\d{10}")))
                .andExpect(jsonPath("$.data[1].vendorTransactionId", matchesPattern("TX-\\d{8}")));

        // Newest-first: LPA ran second, so it leads the history.
        mvc.perform(get("/api/loans/{loanId}/aus/history", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].vendor").value("LPA"))
                .andExpect(jsonPath("$.data[1].vendor").value("DU"));

        assertThat(ausRunCount(loanId)).isEqualTo(2);
    }

    /** No org creds + no loan override → 409 MISSING_CREDENTIALS, and NOTHING is persisted. */
    @Test
    void missingCredentials409() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addBorrower(lo, loanId);
        deleteOrgCredentials("LPA");

        mvc.perform(post("/api/loans/{loanId}/aus/run", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"LPA\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MISSING_CREDENTIALS"))
                .andExpect(jsonPath("$.message", containsString("LPA")));

        assertThat(ausRunCount(loanId)).isEqualTo(0);
    }

    /** REISSUE mode with an empty reference list is a 400 naming creditReferences. */
    @Test
    void reissueWithoutRefs400() throws Exception {
        putOrgCreds("DU");
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addBorrower(lo, loanId);
        mvc.perform(put("/api/loans/{loanId}/aus/profile", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"du\":{\"issueMode\":\"REISSUE\"}}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/loans/{loanId}/aus/run", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"DU\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("creditReferences")));

        assertThat(ausRunCount(loanId)).isEqualTo(0);
    }

    /** ORDER mode mints a REAL credit order whose identifier the run carries. */
    @Test
    void orderModeMintsCreditOrder() throws Exception {
        putOrgCreds("DU");
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addBorrower(lo, loanId);
        mvc.perform(put("/api/loans/{loanId}/aus/profile", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"du\":{\"issueMode\":\"ORDER\"}}"))
                .andExpect(status().isOk());

        var res = mvc.perform(post("/api/loans/{loanId}/aus/run", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"DU\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data[0].creditReportIdentifier", matchesPattern("XS-\\d{8}")))
                .andReturn();
        String runIdentifier = JsonPath.read(res.getResponse().getContentAsString(),
                "$.data[0].creditReportIdentifier");

        Integer orderRows = jdbc.queryForObject(
                "select count(*) from credit_order where loan_id = ?::uuid", Integer.class, loanId);
        assertThat(orderRows).isEqualTo(1);
        String orderIdentifier = jdbc.queryForObject(
                "select credit_report_identifier from credit_order where loan_id = ?::uuid",
                String.class, loanId);
        assertThat(orderIdentifier).isEqualTo(runIdentifier);
    }

    /** Resubmitting to the same vendor reuses the vendor-assigned casefile id (DU semantics). */
    @Test
    void resubmitKeepsCaseId() throws Exception {
        putOrgCreds("DU");
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String borrowerId = addBorrower(lo, loanId);
        putReissueProfile(lo, loanId, "du", borrowerId, "ABC123");

        var first = mvc.perform(post("/api/loans/{loanId}/aus/run", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"DU\"}"))
                .andExpect(status().isCreated()).andReturn();
        String firstCaseId = JsonPath.read(first.getResponse().getContentAsString(),
                "$.data[0].vendorCaseId");

        mvc.perform(post("/api/loans/{loanId}/aus/run", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"DU\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data[0].vendorCaseId").value(firstCaseId));

        assertThat(ausRunCount(loanId)).isEqualTo(2);
    }

    /**
     * Casefile continuity must survive a failed submission: when the LATEST same-vendor row is an
     * ERROR audit row (no vendorCaseId), the resolver skips it and reuses the newest row that
     * actually carries a casefile id. Both prior rows are JDBC-seeded with a sentinel caseId the
     * deterministic stub would never mint — a real first run would mask the regression, because
     * the stub re-mints the SAME id per (loanId, vendor) even when continuity is broken.
     */
    @Test
    void resubmitSkipsErrorRowsWhenResolvingCaseId() throws Exception {
        putOrgCreds("DU");
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String borrowerId = addBorrower(lo, loanId);
        putReissueProfile(lo, loanId, "du", borrowerId, "ABC123");

        // Older COMPLETE run that owns the vendor casefile id (column list mirrors V15;
        // messages/version/timestamps come from the table defaults).
        jdbc.update("""
                insert into aus_run (id, org_id, loan_id, vendor, status, vendor_case_id, requested_at)
                values (?::uuid, ?::uuid, ?::uuid, 'DU', 'COMPLETE', 'DU-SEEDED01', now() - interval '1 hour')
                """, UUID.randomUUID().toString(), DEFAULT_ORG, loanId);
        // Newer failed-submission audit row: status ERROR, NO vendor_case_id.
        jdbc.update("""
                insert into aus_run (id, org_id, loan_id, vendor, status, error_message, requested_at)
                values (?::uuid, ?::uuid, ?::uuid, 'DU', 'ERROR', 'vendor unavailable (seeded)', now())
                """, UUID.randomUUID().toString(), DEFAULT_ORG, loanId);

        mvc.perform(post("/api/loans/{loanId}/aus/run", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"DU\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data[0].vendorCaseId").value("DU-SEEDED01"));

        assertThat(ausRunCount(loanId)).isEqualTo(3);
    }

    @Test
    void crossOrg404() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addBorrower(lo, loanId);

        mvc.perform(post("/api/loans/{loanId}/aus/run", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"DU\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{loanId}/aus/history", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Task 10 — role/negative coverage
    // ------------------------------------------------------------------

    /** Org-wide back-office access: a PROCESSOR who is NOT the LO can run AUS on the loan. */
    @Test
    void processorCanRunAus() throws Exception {
        putOrgCreds("DU");
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String borrowerId = addBorrower(lo, loanId);
        putReissueProfile(lo, loanId, "du", borrowerId, "ABC123");
        String processorSub = UUID.randomUUID().toString();

        mvc.perform(post("/api/loans/{loanId}/aus/run", loanId)
                        .with(as(processorSub, "ROLE_PROCESSOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"DU\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data[0].recommendation").value("APPROVE_ELIGIBLE"))
                .andExpect(jsonPath("$.data[0].requestedBy").value(processorSub));

        assertThat(ausRunCount(loanId)).isEqualTo(1);
    }

    /**
     * LOs are owner-scoped: a DIFFERENT LO in the SAME org gets 403 on another LO's loan.
     * Creds + profile are fully seeded so the rejection can only be the access guard
     * (without the guard this exact request would 201).
     */
    @Test
    void loOnAnotherLosLoan403() throws Exception {
        putOrgCreds("DU");
        String owner = UUID.randomUUID().toString();
        String loanId = createLoan(owner);
        String borrowerId = addBorrower(owner, loanId);
        putReissueProfile(owner, loanId, "du", borrowerId, "ABC123");

        mvc.perform(post("/api/loans/{loanId}/aus/run", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"DU\"}"))
                .andExpect(status().isForbidden());

        assertThat(ausRunCount(loanId)).isEqualTo(0);
    }

    /** Unknown enum constant in the body (vendor=BOTH) → 400 VALIDATION_ERROR (notReadable handler). */
    @Test
    void badVendorEnum400() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(post("/api/loans/{loanId}/aus/run", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"BOTH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(ausRunCount(loanId)).isEqualTo(0);
    }
}
