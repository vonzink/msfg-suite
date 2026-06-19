package com.msfg.los.parties.web;

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

class BorrowerControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addBorrower(String loanId, String first, String last, boolean primary) throws Exception {
        var res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"%s\",\"lastName\":\"%s\",\"primary\":%s}".formatted(first, last, primary)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    @Test
    void addFirstBorrowerIsPrimaryAtOrdinalZero() throws Exception {
        String loanId = createLoan();
        mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Abbas\",\"lastName\":\"Hussein\",\"primary\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.primary").value(true))   // first borrower forced primary
                .andExpect(jsonPath("$.data.ordinal").value(0));
    }

    @Test
    void secondBorrowerGetsOrdinalOneAndNotPrimary() throws Exception {
        String loanId = createLoan();
        mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"A\",\"lastName\":\"H\",\"primary\":true}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"B\",\"lastName\":\"H\",\"primary\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ordinal").value(1))
                .andExpect(jsonPath("$.data.primary").value(false));
        mvc.perform(get("/api/loans/{id}/borrowers", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void cannotAddBorrowerToSomeoneElsesLoan403() throws Exception {
        String loanId = createLoan();   // owned by LO
        mvc.perform(post("/api/loans/{id}/borrowers", loanId)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                              .claim("org_id", DEFAULT_ORG))
                                .authorities(new SimpleGrantedAuthority("ROLE_LO")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"X\",\"lastName\":\"Y\",\"primary\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{id}/borrowers", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deletingPrimaryPromotesNextBorrower() throws Exception {
        String loanId = createLoan();
        String firstId = addBorrower(loanId, "A", "One", false);   // first → forced primary
        addBorrower(loanId, "B", "Two", false);                    // ordinal 1, not primary
        mvc.perform(delete("/api/loans/{l}/borrowers/{b}", loanId, firstId).with(lo()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/loans/{l}/borrowers", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].primary").value(true));   // remaining borrower promoted
    }

    // ── link-user endpoint (T5b) ─────────────────────────────────────────────

    private RequestPostProcessor processor() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_PROCESSOR"));
    }

    @Test
    void staffSetsUserIdLink() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId, "Jane", "Doe", false);
        String userId = UUID.randomUUID().toString();

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/link-user", loanId, borrowerId)
                        .with(processor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(userId));

        // read-back confirms persistence
        mvc.perform(get("/api/loans/{l}/borrowers", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(userId));
    }

    @Test
    void staffOverridesExistingUserId() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId, "John", "Smith", false);
        String firstUserId = UUID.randomUUID().toString();
        String secondUserId = UUID.randomUUID().toString();

        // set initial link
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/link-user", loanId, borrowerId)
                        .with(processor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(firstUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(firstUserId));

        // override with second userId
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/link-user", loanId, borrowerId)
                        .with(processor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(secondUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(secondUserId));
    }

    @Test
    void borrowerRoleDenied403() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId, "Bob", "Borrower", false);
        String userId = UUID.randomUUID().toString();

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/link-user", loanId, borrowerId)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", DEFAULT_ORG))
                                .authorities(new SimpleGrantedAuthority("ROLE_BORROWER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void realEstateAgentRoleDenied403() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId, "Bob", "Agent", false);
        String userId = UUID.randomUUID().toString();

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/link-user", loanId, borrowerId)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", DEFAULT_ORG))
                                .authorities(new SimpleGrantedAuthority("ROLE_REAL_ESTATE_AGENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void platformAdminRoleDenied403() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId, "Bob", "PlatAdmin", false);
        String userId = UUID.randomUUID().toString();

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/link-user", loanId, borrowerId)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", DEFAULT_ORG))
                                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void crossTenantBorrowerId404() throws Exception {
        String loanId = createLoan();
        String userId = UUID.randomUUID().toString();
        // Use a borrowerId that doesn't exist in DEFAULT_ORG
        String crossTenantBorrowerId = UUID.randomUUID().toString();

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/link-user", loanId, crossTenantBorrowerId)
                        .with(processor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void borrowerNotOnLoan404() throws Exception {
        String loan1Id = createLoan();
        String loan2Id = createLoan();
        // borrower belongs to loan2, not loan1
        String borrowerOnLoan2 = addBorrower(loan2Id, "Misplaced", "Borrower", false);
        String userId = UUID.randomUUID().toString();

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/link-user", loan1Id, borrowerOnLoan2)
                        .with(processor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
