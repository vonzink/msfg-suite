package com.msfg.los.identity;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase F T10 — consolidated, end-to-end identity-and-access narrative.
 *
 * <p>Where the per-task ITs ({@code RoleAccessIT}, {@code BorrowerOwnDataReadIT}, {@code MeIT})
 * prove the matrix cell-by-cell, this IT walks the WHOLE Phase F story as one cohesive flow against
 * a real Testcontainers Postgres: an LO builds a loan with two borrowers, staff links borrower A to a
 * Cognito sub and assigns an agent, then every distinct principal — borrower A, the agent, an
 * unlinked borrower, a cross-tenant borrower, and staff — is exercised against the access surface.
 *
 * <p>Every denial asserts the flat envelope ({@code {success:false, code, message, ...}}). Two
 * enforcement layers are crossed together: SecurityConfig deny-by-default at the filter (writes,
 * agents on NPI, loan-level aggregates, documents) and the service guards
 * ({@code assertReadable}/{@code assertBorrowerSelfReadable}/{@code assertCanModify}) for own-vs-co
 * borrower scoping and the tenant wall.
 *
 * <p>Setup uses the real API wherever an endpoint exists (loan/borrower/link-user/agent-assign,
 * income, declarations); JDBC is used only for the cross-tenant loan in a SECOND org, where no
 * in-test cross-org API path exists.
 */
class PhaseFAccessE2EIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    // Owning loan officer for the primary (DEFAULT_ORG) loan.
    static final String LO_A = UUID.randomUUID().toString();
    // A distinct, non-colliding second org for the cross-tenant probe.
    static final String OTHER_ORG = "00000000-0000-0000-0000-0000000000ee";

    private RequestPostProcessor as(String sub, String role) {
        return as(sub, role, DEFAULT_ORG);
    }

    private RequestPostProcessor as(String sub, String role, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org))
                .authorities(new SimpleGrantedAuthority(role));
    }

    // ── setup helpers (real API) ────────────────────────────────────────────────

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO_A)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addBorrower(String loanId, String first, boolean primary) throws Exception {
        var res = mvc.perform(post("/api/loans/{loanId}/borrowers", loanId).with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"%s\",\"lastName\":\"Buyer\",\"primary\":%s}".formatted(first, primary)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private void linkUser(String loanId, String borrowerId, String userSub) throws Exception {
        mvc.perform(post("/api/loans/{loanId}/borrowers/{bid}/link-user", loanId, borrowerId)
                        .with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(userSub)))
                .andExpect(status().isOk());
    }

    private void assignAgent(String loanId, String userSub) throws Exception {
        mvc.perform(post("/api/loans/{loanId}/agents", loanId).with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"agentRole\":\"BUYERS_AGENT\"}".formatted(userSub)))
                .andExpect(status().isCreated());
    }

    private void seedIncome(String loanId, String borrowerId) throws Exception {
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, borrowerId).with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"OTHER\",\"monthlyAmount\":1000}"))
                .andExpect(status().isCreated());
    }

    private void seedDeclarations(String loanId, String borrowerId) throws Exception {
        mvc.perform(put("/api/loans/{l}/borrowers/{b}/declarations", loanId, borrowerId).with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private boolean myLoansContains(RequestPostProcessor who, String loanId) throws Exception {
        int page = 0;
        while (true) {
            String body = mvc.perform(get("/api/me/loans?size=100&page=" + page).with(who))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var doc = JsonPath.parse(body);
            List<String> ids = doc.read("$.data.items[*].id");
            if (ids.contains(loanId)) return true;
            int totalPages = doc.read("$.data.totalPages", Integer.class);
            if (++page >= totalPages) return false;
        }
    }

    /** Asserts a 403 carrying the flat FORBIDDEN envelope. */
    private void expectForbidden(org.springframework.test.web.servlet.ResultActions ra) throws Exception {
        ra.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").exists());
    }

    // ── the narrative ───────────────────────────────────────────────────────────

    @Test
    void borrowerJourney_ownReads_coBorrowerAndAggregatesAndWritesDenied() throws Exception {
        // SETUP — LO builds the loan, two borrowers, links A, assigns an agent, seeds data.
        String loan = createLoan();
        String borrowerA = addBorrower(loan, "Alice", true);
        String borrowerB = addBorrower(loan, "Bob", false);
        String subA = UUID.randomUUID().toString();
        String agentX = UUID.randomUUID().toString();
        linkUser(loan, borrowerA, subA);
        assignAgent(loan, agentX);
        seedIncome(loan, borrowerA);
        seedIncome(loan, borrowerB);
        seedDeclarations(loan, borrowerA);
        seedDeclarations(loan, borrowerB);

        RequestPostProcessor A = as(subA, "ROLE_BORROWER");

        // ALLOWED — own loan summary + transitions
        mvc.perform(get("/api/loans/{id}", loan).with(A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(loan));
        mvc.perform(get("/api/loans/{id}/status/transitions", loan).with(A))
                .andExpect(status().isOk());

        // ALLOWED — /me/loans contains exactly this one loan
        assertThat(myLoansContains(A, loan)).as("borrower A sees own loan in /me/loans").isTrue();
        String meBody = mvc.perform(get("/api/me/loans?size=100&page=0").with(A))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.parse(meBody).read("$.data.total"))
                .as("borrower A's /me/loans holds exactly the one linked loan").isEqualTo(1);

        // ALLOWED — own per-borrower 1003 data (income + declarations)
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loan, borrowerA).with(A))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/declarations", loan, borrowerA).with(A))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

        // DENIED — co-borrower B's per-borrower data (service self-scoping)
        expectForbidden(mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loan, borrowerB).with(A)));
        expectForbidden(mvc.perform(get("/api/loans/{l}/borrowers/{b}/declarations", loan, borrowerB).with(A)));

        // DENIED — loan-level aggregate (staff-only at the filter)
        expectForbidden(mvc.perform(get("/api/loans/{l}/income/summary", loan).with(A)));

        // DENIED — any write
        expectForbidden(mvc.perform(patch("/api/loans/{id}", loan).with(A)
                .contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Austin\"}")));
        expectForbidden(mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loan, borrowerA).with(A)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"incomeType\":\"OTHER\",\"monthlyAmount\":50}")));

        // DENIED — reveal-ssn (NPI)
        expectForbidden(mvc.perform(post("/api/loans/{l}/borrowers/{b}/reveal-ssn", loan, borrowerA).with(A)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")));
    }

    @Test
    void agentJourney_summaryAndMeLoans_butNoNpiNoDocumentsNoWrites() throws Exception {
        String loan = createLoan();
        String borrowerA = addBorrower(loan, "Alice", true);
        String agentX = UUID.randomUUID().toString();
        assignAgent(loan, agentX);

        RequestPostProcessor X = as(agentX, "ROLE_REAL_ESTATE_AGENT");

        // ALLOWED — loan summary + /me/loans contains this loan
        mvc.perform(get("/api/loans/{id}", loan).with(X))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(loan));
        assertThat(myLoansContains(X, loan)).as("agent X sees the assigned loan in /me/loans").isTrue();

        // DENIED — any borrower NPI (per-borrower income, reveal-ssn)
        expectForbidden(mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loan, borrowerA).with(X)));
        expectForbidden(mvc.perform(post("/api/loans/{l}/borrowers/{b}/reveal-ssn", loan, borrowerA).with(X)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")));

        // DENIED — documents
        expectForbidden(mvc.perform(get("/api/loans/{id}/documents", loan).with(X)));

        // DENIED — any write
        expectForbidden(mvc.perform(patch("/api/loans/{id}", loan).with(X)
                .contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Austin\"}")));
    }

    @Test
    void unlinkedBorrower_cannotReachTheLoan() throws Exception {
        String loan = createLoan();
        addBorrower(loan, "Alice", true);   // a borrower exists, but the probe sub is NOT linked

        // A random BORROWER sub with no link → not on the loan → 403 at the service guard.
        expectForbidden(mvc.perform(get("/api/loans/{id}", loan)
                .with(as(UUID.randomUUID().toString(), "ROLE_BORROWER"))));
    }

    @Test
    void crossTenantBorrower_linkedInAnotherOrg_gets404AndNeverListed() throws Exception {
        // The primary loan lives in DEFAULT_ORG.
        String loan = createLoan();

        // Seed a SECOND org + a loan there + a borrower linked to `foreignSub` in THAT org (JDBC:
        // no in-test cross-org API path). Same sub, different org.
        jdbc.update(
                "insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,'pf-e2e-other','pf-e2e-other','ACTIVE','{}'::jsonb) on conflict (id) do nothing",
                OTHER_ORG);
        UUID foreignLoan = UUID.randomUUID();
        String foreignSub = UUID.randomUUID().toString();
        jdbc.update(
                "insert into loan (id,version,loan_number,loan_officer_id,status,org_id) " +
                "values (?,0,?,?,'STARTED',?::uuid)",
                foreignLoan, "PFE2E-" + foreignLoan.toString().substring(0, 8), UUID.randomUUID(), OTHER_ORG);
        jdbc.update(
                "insert into borrower_party (id,version,org_id,loan_id,is_primary,ordinal,email,user_id) " +
                "values (?,0,?::uuid,?,true,0,?,?::uuid)",
                UUID.randomUUID(), OTHER_ORG, foreignLoan, foreignLoan + "@other.local", foreignSub);

        RequestPostProcessor foreign = as(foreignSub, "ROLE_BORROWER", OTHER_ORG);

        // The DEFAULT_ORG loan is invisible to an OTHER_ORG token → tenant filter → 404 (not 403).
        mvc.perform(get("/api/loans/{id}", loan).with(foreign))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));

        // And /me/loans for the foreign token never lists the DEFAULT_ORG loan.
        assertThat(myLoansContains(foreign, loan))
                .as("cross-tenant borrower's /me/loans never lists the other org's loan").isFalse();
    }

    @Test
    void staffRegression_owningLoAndProcessor_readLoanAndBorrowerIncome() throws Exception {
        String loan = createLoan();
        String borrowerA = addBorrower(loan, "Alice", true);
        seedIncome(loan, borrowerA);

        // Owning LO — reads loan + the borrower's per-borrower income.
        mvc.perform(get("/api/loans/{id}", loan).with(as(LO_A, "ROLE_LO")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loan, borrowerA).with(as(LO_A, "ROLE_LO")))
                .andExpect(status().isOk());

        // Org-wide PROCESSOR (a different sub) — unaffected by Phase F party scoping.
        String processor = UUID.randomUUID().toString();
        mvc.perform(get("/api/loans/{id}", loan).with(as(processor, "ROLE_PROCESSOR")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loan, borrowerA).with(as(processor, "ROLE_PROCESSOR")))
                .andExpect(status().isOk());
    }
}
