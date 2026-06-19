package com.msfg.los.identity.web;

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

/**
 * Phase F T11 — per-borrower own-data reads.
 *
 * <p>A linked BORROWER may READ their OWN per-borrower 1003 data (income/employments/assets/
 * liabilities/declarations/demographics) — never a co-borrower's, never loan-level aggregates,
 * never writes. Agents are denied. Staff (PROCESSOR / owning-LO) are unchanged.
 *
 * <p>Two layers asserted together with REAL distinct principals: SecurityConfig deny-by-default at
 * the filter (write verbs, agents, aggregates → 403) and {@code LoanAccessGuard.assertBorrowerSelfReadable}
 * self-scoping at the service (co-borrower's id → 403).
 */
class BorrowerOwnDataReadIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO_A = UUID.randomUUID().toString();

    private RequestPostProcessor as(String sub, String role) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO_A)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Adds a borrower (optionally primary) and returns its id. */
    private String addBorrower(String loanId, String first, boolean primary) throws Exception {
        var res = mvc.perform(post("/api/loans/{loanId}/borrowers", loanId).with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"%s\",\"lastName\":\"Buyer\",\"primary\":%s}".formatted(first, primary)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Links a borrower row to a user sub (the borrower-self link). */
    private void linkUser(String loanId, String borrowerId, String userSub) throws Exception {
        mvc.perform(post("/api/loans/{loanId}/borrowers/{bid}/link-user", loanId, borrowerId)
                        .with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(userSub)))
                .andExpect(status().isOk());
    }

    // ── representative LIST-type module: income ──────────────────────────────────────────

    @Test
    void borrowerReadsOwnIncome200() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myBorrowerId = addBorrower(loan, "Pat", true);
        linkUser(loan, myBorrowerId, me);
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loan, myBorrowerId)
                        .with(as(me, "ROLE_BORROWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void borrowerReadingCoBorrowerIncomeForbidden() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myBorrowerId = addBorrower(loan, "Pat", true);
        String coBorrowerId = addBorrower(loan, "Sam", false);   // a different borrower row
        linkUser(loan, myBorrowerId, me);
        // The path passes the SecurityConfig filter (BORROWER + UUID shape), but the service guard
        // denies because `me` is not the co-borrower row → 403 via assertBorrowerSelfReadable.
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loan, coBorrowerId)
                        .with(as(me, "ROLE_BORROWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void agentReadingBorrowerIncomeForbidden() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myBorrowerId = addBorrower(loan, "Pat", true);
        linkUser(loan, myBorrowerId, me);
        // Agent is excluded at the filter (not in STAFF_AND_BORROWER) → 403 before the service.
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loan, myBorrowerId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_REAL_ESTATE_AGENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void borrowerOnLoanLevelIncomeSummaryForbidden() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myBorrowerId = addBorrower(loan, "Pat", true);
        linkUser(loan, myBorrowerId, me);
        // Loan-level aggregate stays staff-only → 403 at the filter (not in any party allowlist).
        mvc.perform(get("/api/loans/{l}/income/summary", loan).with(as(me, "ROLE_BORROWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void borrowerWritesOwnIncomeForbidden() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myBorrowerId = addBorrower(loan, "Pat", true);
        linkUser(loan, myBorrowerId, me);
        RequestPostProcessor who = as(me, "ROLE_BORROWER");
        // Writes on the in-scope path stay staff-only — the T11 GET allowlist is GET-only, so
        // POST/PATCH fall through to the staff-only catch-all → 403 at the filter.
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loan, myBorrowerId).with(who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"OTHER\",\"monthlyAmount\":100}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/loans/{l}/borrowers/{b}/income/{i}", loan, myBorrowerId, UUID.randomUUID()).with(who)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyAmount\":200}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/loans/{l}/borrowers/{b}/income/{i}", loan, myBorrowerId, UUID.randomUUID()).with(who))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffReadsBorrowerIncomeUnchanged200() throws Exception {
        String loan = createLoan();
        String myBorrowerId = addBorrower(loan, "Pat", true);
        // Owning LO and an org-wide PROCESSOR both still read per-borrower data.
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loan, myBorrowerId).with(as(LO_A, "ROLE_LO")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loan, myBorrowerId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR")))
                .andExpect(status().isOk());
    }

    // ── 1:1-type module: declarations ────────────────────────────────────────────────────

    @Test
    void borrowerReadsOwnDeclarations200() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myBorrowerId = addBorrower(loan, "Pat", true);
        linkUser(loan, myBorrowerId, me);
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/declarations", loan, myBorrowerId)
                        .with(as(me, "ROLE_BORROWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void borrowerReadingCoBorrowerDeclarationsForbidden() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myBorrowerId = addBorrower(loan, "Pat", true);
        String coBorrowerId = addBorrower(loan, "Sam", false);
        linkUser(loan, myBorrowerId, me);
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/declarations", loan, coBorrowerId)
                        .with(as(me, "ROLE_BORROWER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void borrowerWritesOwnDeclarationsForbidden() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myBorrowerId = addBorrower(loan, "Pat", true);
        linkUser(loan, myBorrowerId, me);
        // PUT-upsert stays staff-only (write) → 403 at the filter.
        mvc.perform(put("/api/loans/{l}/borrowers/{b}/declarations", loan, myBorrowerId)
                        .with(as(me, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffReadsBorrowerDeclarationsUnchanged200() throws Exception {
        String loan = createLoan();
        String myBorrowerId = addBorrower(loan, "Pat", true);
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/declarations", loan, myBorrowerId).with(as(LO_A, "ROLE_LO")))
                .andExpect(status().isOk());
    }
}
