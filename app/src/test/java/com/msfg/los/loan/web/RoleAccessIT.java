package com.msfg.los.loan.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Loan access matrix.
 *
 * <p>Staff org-wide / owning-LO scoping (spec 2026-06-11): PROCESSOR/UNDERWRITER/CLOSER/MANAGER vs
 * LO/PLATFORM_ADMIN.
 *
 * <p>Phase F T6 — a linked BORROWER / REAL_ESTATE_AGENT token may reach ONLY a small read allowlist
 * (loan summary + status transitions) for THEIR OWN loan, and NEVER any write, NPI, or non-allowlisted
 * read. Enforced in two layers: SecurityConfig deny-by-default at the filter, plus
 * {@code LoanAccessGuard.assertReadable} self-scoping at the controller.
 */
class RoleAccessIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    static final String LO_A = UUID.randomUUID().toString();

    private RequestPostProcessor as(String sub, String role) {
        return as(sub, role, DEFAULT_ORG);
    }

    private RequestPostProcessor as(String sub, String role, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private String createLoanOwnedByLoA() throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO_A)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Adds a borrower to the loan and links them to {@code userSub}, returning the borrower id. */
    private String addLinkedBorrower(String loanId, String userSub) throws Exception {
        var res = mvc.perform(post("/api/loans/{loanId}/borrowers", loanId).with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Pat\",\"lastName\":\"Buyer\",\"primary\":true}"))
                .andExpect(status().isCreated()).andReturn();
        String borrowerId = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
        mvc.perform(post("/api/loans/{loanId}/borrowers/{bid}/link-user", loanId, borrowerId)
                        .with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(userSub)))
                .andExpect(status().isOk());
        return borrowerId;
    }

    /** Inserts a loan_agent row linking {@code userSub} as a buyer's agent on the loan (in DEFAULT_ORG). */
    private void linkAgent(String loanId, String userSub) {
        jdbc.update("insert into loan_agent (id, org_id, loan_id, user_id, agent_role, ordinal) "
                        + "values (?, ?::uuid, ?::uuid, ?::uuid, 'BUYERS_AGENT', 0)",
                UUID.randomUUID(), DEFAULT_ORG, loanId, userSub);
    }

    /** Pages through GET /api/loans as `who` and reports whether loanId appears anywhere. */
    private boolean pipelineContains(RequestPostProcessor who, String loanId) throws Exception {
        int page = 0;
        while (true) {
            String body = mvc.perform(get("/api/loans?size=100&page=" + page).with(who))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            com.jayway.jsonpath.DocumentContext doc = com.jayway.jsonpath.JsonPath.parse(body);
            java.util.List<String> ids = doc.read("$.data.items[*].id");
            if (ids.contains(loanId)) return true;
            int totalPages = doc.read("$.data.totalPages", Integer.class);
            if (++page >= totalPages) return false;
        }
    }

    // --- ops roles can open another LO's loan ---

    @Test
    void processorCanReadAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void underwriterCanReadAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_UNDERWRITER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void closerCanReadAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_CLOSER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }

    // --- ops pipeline is org-wide ---

    @Test
    void processorPipelineListsOtherLosLoans() throws Exception {
        String id = createLoanOwnedByLoA();
        org.assertj.core.api.Assertions.assertThat(
                pipelineContains(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR"), id)).isTrue();
    }

    // --- LO stays scoped ---

    @Test
    void loPipelineDoesNotListOtherLosLoans() throws Exception {
        String id = createLoanOwnedByLoA();
        org.assertj.core.api.Assertions.assertThat(
                pipelineContains(as(UUID.randomUUID().toString(), "ROLE_LO"), id)).isFalse();
    }

    // --- PLATFORM_ADMIN pinned out of loan data ---

    @Test
    void platformAdminCannotReadLoans403() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());
    }

    // --- role breadth never crosses the tenant wall ---

    @Test
    void processorCrossOrgStill404() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff")))
                .andExpect(status().isNotFound());
    }

    @Test
    void processorPipelineNeverListsOtherOrgsLoans() throws Exception {
        String id = createLoanOwnedByLoA();   // lives in DEFAULT_ORG
        boolean visible = pipelineContains(
                as(UUID.randomUUID().toString(), "ROLE_PROCESSOR", "ffffffff-ffff-ffff-ffff-ffffffffffff"), id);
        org.assertj.core.api.Assertions.assertThat(visible).isFalse();
    }

    // ── MANAGER staff regression — org-wide like the other back-office roles ──────────────

    @Test
    void managerCanReadAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void managerCanWriteAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(patch("/api/loans/{id}", id).with(as(UUID.randomUUID().toString(), "ROLE_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Austin\"}"))
                .andExpect(status().isOk());
    }

    // ── ADMIN staff regression — full loan read/write ────────────────────────────────────

    @Test
    void adminCanReadAndWriteAnotherLosLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        String admin = UUID.randomUUID().toString();
        mvc.perform(get("/api/loans/{id}", id).with(as(admin, "ROLE_ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/loans/{id}", id).with(as(admin, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Austin\"}"))
                .andExpect(status().isOk());
    }

    // ── Owning LO regression — read + write own loan ─────────────────────────────────────

    @Test
    void owningLoCanReadAndWriteOwnLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", id).with(as(LO_A, "ROLE_LO")))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/loans/{id}", id).with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Austin\"}"))
                .andExpect(status().isOk());
    }

    // ── Linked BORROWER — allowlisted reads on OWN loan ──────────────────────────────────

    @Test
    void linkedBorrowerCanReadOwnLoanSummary() throws Exception {
        String id = createLoanOwnedByLoA();
        String borrowerSub = UUID.randomUUID().toString();
        addLinkedBorrower(id, borrowerSub);
        mvc.perform(get("/api/loans/{id}", id).with(as(borrowerSub, "ROLE_BORROWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void linkedBorrowerCanReadOwnLoanTransitions() throws Exception {
        String id = createLoanOwnedByLoA();
        String borrowerSub = UUID.randomUUID().toString();
        addLinkedBorrower(id, borrowerSub);
        mvc.perform(get("/api/loans/{id}/status/transitions", id).with(as(borrowerSub, "ROLE_BORROWER")))
                .andExpect(status().isOk());
    }

    @Test
    void linkedBorrowerCanReachMe() throws Exception {
        String borrowerSub = UUID.randomUUID().toString();
        mvc.perform(get("/api/me").with(as(borrowerSub, "ROLE_BORROWER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me/loans").with(as(borrowerSub, "ROLE_BORROWER")))
                .andExpect(status().isOk());
    }

    // ── Linked BORROWER — denied every write / NPI / non-allowlisted read ────────────────

    @Test
    void linkedBorrowerCannotWriteLoan() throws Exception {
        String id = createLoanOwnedByLoA();
        String borrowerSub = UUID.randomUUID().toString();
        addLinkedBorrower(id, borrowerSub);
        mvc.perform(patch("/api/loans/{id}", id).with(as(borrowerSub, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Austin\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/loans/{id}/status", id).with(as(borrowerSub, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetStatus\":\"PROCESSING\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/loans/{id}", id).with(as(borrowerSub, "ROLE_BORROWER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/loans").with(as(borrowerSub, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void linkedBorrowerCannotRevealSsnOrLinkUser() throws Exception {
        String id = createLoanOwnedByLoA();
        String borrowerSub = UUID.randomUUID().toString();
        String borrowerId = addLinkedBorrower(id, borrowerSub);
        mvc.perform(post("/api/loans/{id}/borrowers/{bid}/reveal-ssn", id, borrowerId)
                        .with(as(borrowerSub, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/loans/{id}/borrowers/{bid}/link-user", id, borrowerId)
                        .with(as(borrowerSub, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void linkedBorrowerCannotReadIncomeFinancialsDeclarations() throws Exception {
        String id = createLoanOwnedByLoA();
        String borrowerSub = UUID.randomUUID().toString();
        String borrowerId = addLinkedBorrower(id, borrowerSub);
        RequestPostProcessor who = as(borrowerSub, "ROLE_BORROWER");
        mvc.perform(get("/api/loans/{id}/income/summary", id).with(who))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/loans/{id}/assets/summary", id).with(who))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/loans/{id}/borrowers/{bid}/declarations", id, borrowerId).with(who))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/loans/{id}/borrowers", id).with(who))
                .andExpect(status().isForbidden());
    }

    @Test
    void linkedBorrowerCannotWriteConditionsOrNotes() throws Exception {
        String id = createLoanOwnedByLoA();
        String borrowerSub = UUID.randomUUID().toString();
        addLinkedBorrower(id, borrowerSub);
        RequestPostProcessor who = as(borrowerSub, "ROLE_BORROWER");
        mvc.perform(post("/api/loans/{id}/conditions", id).with(who)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/loans/{id}/notes", id).with(who)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void linkedBorrowerCannotReadLoanTheyAreNotLinkedTo() throws Exception {
        String linkedId = createLoanOwnedByLoA();
        String borrowerSub = UUID.randomUUID().toString();
        addLinkedBorrower(linkedId, borrowerSub);
        String otherId = createLoanOwnedByLoA();   // borrower NOT linked here
        mvc.perform(get("/api/loans/{id}", otherId).with(as(borrowerSub, "ROLE_BORROWER")))
                .andExpect(status().isForbidden());
    }

    // ── Linked REAL_ESTATE_AGENT — allowlisted read on OWN loan, denied otherwise ────────

    @Test
    void linkedAgentCanReadOwnLoanSummary() throws Exception {
        String id = createLoanOwnedByLoA();
        String agentSub = UUID.randomUUID().toString();
        linkAgent(id, agentSub);
        mvc.perform(get("/api/loans/{id}", id).with(as(agentSub, "ROLE_REAL_ESTATE_AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    void linkedAgentCannotWriteOrReadNpiOrDocuments() throws Exception {
        String id = createLoanOwnedByLoA();
        String agentSub = UUID.randomUUID().toString();
        linkAgent(id, agentSub);
        RequestPostProcessor who = as(agentSub, "ROLE_REAL_ESTATE_AGENT");
        mvc.perform(patch("/api/loans/{id}", id).with(who)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Austin\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/loans/{id}/borrowers", id).with(who))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/loans/{id}/income/summary", id).with(who))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/loans/{id}/documents", id).with(who))
                .andExpect(status().isForbidden());
    }

    @Test
    void linkedAgentCannotReadLoanTheyAreNotLinkedTo() throws Exception {
        String linkedId = createLoanOwnedByLoA();
        String agentSub = UUID.randomUUID().toString();
        linkAgent(linkedId, agentSub);
        String otherId = createLoanOwnedByLoA();
        mvc.perform(get("/api/loans/{id}", otherId).with(as(agentSub, "ROLE_REAL_ESTATE_AGENT")))
                .andExpect(status().isForbidden());
    }

    // ── LO hardening — a BORROWER whose sub == loanOfficerId is still NOT the LO ──────────

    @Test
    void borrowerWhoseSubEqualsLoanOfficerIdIsStillDeniedWrites() throws Exception {
        // Loan owned by LO_A. A borrower token carrying sub == LO_A must NOT be treated as the LO.
        String id = createLoanOwnedByLoA();
        addLinkedBorrower(id, LO_A);
        // Allowlisted read still works (linked borrower), but writes are denied — they are not staff.
        mvc.perform(patch("/api/loans/{id}", id).with(as(LO_A, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Austin\"}"))
                .andExpect(status().isForbidden());
    }

    // ── Cross-tenant — a party linked in another org never reaches this org's loan ───────

    @Test
    void borrowerInAnotherOrgCannotReachThisLoan() throws Exception {
        String id = createLoanOwnedByLoA();   // DEFAULT_ORG
        String borrowerSub = UUID.randomUUID().toString();
        addLinkedBorrower(id, borrowerSub);   // linked in DEFAULT_ORG
        // Same sub, but a token scoped to a different org → tenant filter hides the loan → 404.
        mvc.perform(get("/api/loans/{id}", id)
                        .with(as(borrowerSub, "ROLE_BORROWER", "ffffffff-ffff-ffff-ffff-ffffffffffff")))
                .andExpect(status().isNotFound());
    }
}
