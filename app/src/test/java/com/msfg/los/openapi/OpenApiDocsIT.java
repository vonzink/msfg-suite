package com.msfg.los.openapi;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression guard: ensures GET /v3/api-docs returns HTTP 200 and parseable OpenAPI JSON.
 *
 * <p>A 500 here is typically caused by a springdoc schema-name collision (two classes sharing
 * the same simple name but different packages, when springdoc.use-fqn=false, the default).
 *
 * <p>The stable-operationId assertions exist so that if someone renames or reorders an endpoint
 * the generated TypeScript client doesn't silently churn method names across unrelated areas.
 */
class OpenApiDocsIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void openApiDocs_returns200WithValidJson() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/loans']").exists());
    }

    // ── LoanController stable operationIds ───────────────────────────────────

    @Test
    void loanController_createLoan_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans'].post.operationId").value("createLoan"));
    }

    @Test
    void loanController_listLoans_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans'].get.operationId").value("listLoans"));
    }

    @Test
    void loanController_getLoan_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans/{id}'].get.operationId").value("getLoan"));
    }

    @Test
    void loanController_getLoanByNumber_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans/number/{loanNumber}'].get.operationId").value("getLoanByNumber"));
    }

    @Test
    void loanController_searchLoans_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans/search'].get.operationId").value("searchLoans"));
    }

    @Test
    void loanController_deleteLoan_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans/{id}'].delete.operationId").value("deleteLoan"));
    }

    @Test
    void loanController_updateLoan_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans/{id}'].patch.operationId").value("updateLoan"));
    }

    @Test
    void loanController_getLoanStatusTransitions_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans/{id}/status/transitions'].get.operationId").value("getLoanStatusTransitions"));
    }

    @Test
    void loanController_transitionLoanStatus_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans/{id}/status'].post.operationId").value("transitionLoanStatus"));
    }

    // ── MeController stable operationIds ─────────────────────────────────────

    @Test
    void meController_getMe_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/me'].get.operationId").value("getMe"));
    }

    @Test
    void meController_getMyLoans_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/me/loans'].get.operationId").value("getMyLoans"));
    }

    // ── BorrowerController Phase F operationIds ───────────────────────────────

    @Test
    void borrowerController_linkBorrowerUser_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/api/loans/{loanId}/borrowers/{borrowerId}/link-user'].post.operationId")
                        .value("linkBorrowerUser"));
    }

    // ── LoanAgentController Phase F operationIds ──────────────────────────────

    @Test
    void loanAgentController_assignLoanAgent_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans/{loanId}/agents'].post.operationId").value("assignLoanAgent"));
    }

    @Test
    void loanAgentController_listLoanAgents_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans/{loanId}/agents'].get.operationId").value("listLoanAgents"));
    }

    @Test
    void loanAgentController_unassignLoanAgent_operationIdIsStable() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/loans/{loanId}/agents/{agentId}'].delete.operationId").value("unassignLoanAgent"));
    }
}
