package com.msfg.los.loan.web;

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

/**
 * Integration test for {@code /api/loans/{loanId}/agents} (T8 — staff-gated agent assignment CRUD).
 *
 * <p>Cases covered:
 * <ul>
 *   <li>POST (assign) → 201, response echoes id/userId/agentRole/ordinal</li>
 *   <li>Ordinal starts at 0, second assignment ordinal = 1</li>
 *   <li>Duplicate (org, loan, user) → 409</li>
 *   <li>GET (list) → 200, ordered by ordinal</li>
 *   <li>DELETE (unassign) → 204; subsequent GET excludes it</li>
 *   <li>DELETE then assign → ordinal = max+1 (not count)</li>
 *   <li>BORROWER POST → 403 (filter-layer deny)</li>
 *   <li>REAL_ESTATE_AGENT POST → 403 (cannot self-assign)</li>
 *   <li>PLATFORM_ADMIN GET → 403</li>
 *   <li>Cross-org JWT → 404</li>
 *   <li>Wrong agentId on right loan → 404</li>
 *   <li>No token → 401</li>
 *   <li>Missing userId → 400</li>
 *   <li>Missing agentRole → 400</li>
 *   <li>PROCESSOR org-wide read → 200</li>
 *   <li>E2E: staff assigns agent → agent can GET /api/loans/{id} → /me/loans includes the loan</li>
 * </ul>
 */
class LoanAgentControllerIT extends AbstractIntegrationTest {

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

    private String assignAgent(String loanId, String userId, String role) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/agents", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"agentRole\":\"%s\"}".formatted(userId, role)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- POST → 201, echoes all fields, ordinal 0 ---

    @Test
    void assignReturns201WithOrdinalZero() throws Exception {
        String loanId = createLoan();
        String userId = UUID.randomUUID().toString();

        mvc.perform(post("/api/loans/{l}/agents", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"agentRole\":\"BUYERS_AGENT\"}".formatted(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(notNullValue()))
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.agentRole").value("BUYERS_AGENT"))
                .andExpect(jsonPath("$.data.ordinal").value(0));
    }

    // --- second assign ordinal = 1 ---

    @Test
    void secondAssignOrdinalOne() throws Exception {
        String loanId = createLoan();
        assignAgent(loanId, UUID.randomUUID().toString(), "BUYERS_AGENT");

        mvc.perform(post("/api/loans/{l}/agents", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"agentRole\":\"LISTING_AGENT\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ordinal").value(1));
    }

    // --- duplicate (org, loan, user) → 409 ---

    @Test
    void duplicateAssign409() throws Exception {
        String loanId = createLoan();
        String userId = UUID.randomUUID().toString();
        assignAgent(loanId, userId, "BUYERS_AGENT");

        mvc.perform(post("/api/loans/{l}/agents", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"agentRole\":\"DUAL_AGENT\"}".formatted(userId)))
                .andExpect(status().isConflict());
    }

    // --- GET list → 200, ordered by ordinal ---

    @Test
    void listOrderedByOrdinal() throws Exception {
        String loanId = createLoan();
        String u1 = UUID.randomUUID().toString();
        String u2 = UUID.randomUUID().toString();
        assignAgent(loanId, u1, "BUYERS_AGENT");
        assignAgent(loanId, u2, "LISTING_AGENT");

        mvc.perform(get("/api/loans/{l}/agents", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].userId").value(u1))
                .andExpect(jsonPath("$.data[0].ordinal").value(0))
                .andExpect(jsonPath("$.data[1].userId").value(u2))
                .andExpect(jsonPath("$.data[1].ordinal").value(1));
    }

    // --- DELETE → 204, subsequent list excludes it ---

    @Test
    void deleteUnassigns() throws Exception {
        String loanId = createLoan();
        String userId = UUID.randomUUID().toString();
        String agentId = assignAgent(loanId, userId, "DUAL_AGENT");

        mvc.perform(delete("/api/loans/{l}/agents/{a}", loanId, agentId).with(lo()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/loans/{l}/agents", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- delete then assign → ordinal max+1, not reused count ---

    @Test
    void deleteThenOrdinalMaxPlusOne() throws Exception {
        String loanId = createLoan();
        String firstId = assignAgent(loanId, UUID.randomUUID().toString(), "BUYERS_AGENT");  // ordinal 0
        assignAgent(loanId, UUID.randomUUID().toString(), "LISTING_AGENT");                  // ordinal 1

        mvc.perform(delete("/api/loans/{l}/agents/{a}", loanId, firstId).with(lo()))
                .andExpect(status().isNoContent());

        // survivor holds ordinal 1; count-based approach would reuse 1 — must be max+1 = 2
        mvc.perform(post("/api/loans/{l}/agents", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"agentRole\":\"DUAL_AGENT\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ordinal").value(2));
    }

    // --- PROCESSOR org-wide read → 200 ---

    @Test
    void processorOrgWide200() throws Exception {
        String loanId = createLoan();
        assignAgent(loanId, UUID.randomUUID().toString(), "BUYERS_AGENT");

        mvc.perform(get("/api/loans/{l}/agents", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // --- BORROWER POST → 403 (filter-layer deny) ---

    @Test
    void borrowerPost403() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/agents", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"agentRole\":\"BUYERS_AGENT\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    // --- REAL_ESTATE_AGENT POST → 403 (cannot self-assign) ---

    @Test
    void agentPost403CannotSelfAssign() throws Exception {
        String loanId = createLoan();
        String agentSub = UUID.randomUUID().toString();

        mvc.perform(post("/api/loans/{l}/agents", loanId)
                        .with(as(agentSub, "ROLE_REAL_ESTATE_AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"agentRole\":\"BUYERS_AGENT\"}".formatted(agentSub)))
                .andExpect(status().isForbidden());
    }

    // --- REAL_ESTATE_AGENT GET → 403 (staff-only catch-all) ---

    @Test
    void agentGet403() throws Exception {
        String loanId = createLoan();

        mvc.perform(get("/api/loans/{l}/agents", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_REAL_ESTATE_AGENT")))
                .andExpect(status().isForbidden());
    }

    // --- PLATFORM_ADMIN GET → 403 ---

    @Test
    void platformAdmin403() throws Exception {
        String loanId = createLoan();

        mvc.perform(get("/api/loans/{l}/agents", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());
    }

    // --- cross-org JWT → 404 ---

    @Test
    void crossOrg404() throws Exception {
        String loanId = createLoan();

        mvc.perform(get("/api/loans/{l}/agents", loanId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO",
                                "ffffffff-ffff-ffff-ffff-ffffffffffff")))
                .andExpect(status().isNotFound());
    }

    // --- wrong agentId on correct loan → 404 ---

    @Test
    void wrongAgentId404() throws Exception {
        String loanId = createLoan();

        mvc.perform(delete("/api/loans/{l}/agents/{a}", loanId, UUID.randomUUID()).with(lo()))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/agents", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // --- missing userId → 400 ---

    @Test
    void missingUserId400() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/agents", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentRole\":\"BUYERS_AGENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.userId", notNullValue()));
    }

    // --- missing agentRole → 400 ---

    @Test
    void missingAgentRole400() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/agents", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.agentRole", notNullValue()));
    }

    // ── E2E ──────────────────────────────────────────────────────────────────
    // Staff assigns a user as agent → that user can:
    //   (a) GET /api/loans/{id} (summary, 200)
    //   (b) GET /api/me/loans includes the loan (proves T7 linkage)

    @Test
    void e2eStaffAssignsThenAgentCanReadLoanAndSeesItInMyLoans() throws Exception {
        // 1. LO creates a loan
        String loanId = createLoan();

        // 2. Staff (LO-owner) assigns a real-estate agent user
        String agentSub = UUID.randomUUID().toString();
        assignAgent(loanId, agentSub, "BUYERS_AGENT");

        RequestPostProcessor agentToken = as(agentSub, "ROLE_REAL_ESTATE_AGENT");

        // 3. The agent can now read the loan summary
        mvc.perform(get("/api/loans/{id}", loanId).with(agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(loanId));

        // 4. /me/loans for the agent includes the loan (T7 integration; PagedResponse uses "items")
        mvc.perform(get("/api/me/loans").with(agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(loanId));
    }
}
