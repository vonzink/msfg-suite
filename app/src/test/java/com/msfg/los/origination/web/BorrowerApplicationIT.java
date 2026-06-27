package com.msfg.los.origination.web;

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
 * Stage 2 — borrower-self application read/write ({@code GET}/{@code PUT /api/loans/{id}/application}).
 *
 * <p>The regulated write boundary: a linked BORROWER may read + write their OWN 1003 to the suite
 * (system of record); a co-borrower's loan / an unlinked borrower / an agent / PLATFORM_ADMIN are
 * denied; staff (owning-LO) may write (targeting the primary). Two crown-jewel invariants:
 * (1) the borrower's §4 SUBSET PUT NEVER wipes an LO-set staff field (interestRate) — merge-safety;
 * (2) cross-tenant is invisible (404). Asserted with REAL distinct principals.
 */
class BorrowerApplicationIT extends AbstractIntegrationTest {

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

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO_A)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addBorrower(String loanId, String first, boolean primary) throws Exception {
        var res = mvc.perform(post("/api/loans/{loanId}/borrowers", loanId).with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"%s\",\"lastName\":\"Buyer\",\"primary\":%s}".formatted(first, primary)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private void linkUser(String loanId, String borrowerId, String userSub) throws Exception {
        mvc.perform(post("/api/loans/{loanId}/borrowers/{bid}/link-user", loanId, borrowerId)
                        .with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(userSub)))
                .andExpect(status().isOk());
    }

    private void patchLoanAsLo(String loanId, String json) throws Exception {
        mvc.perform(patch("/api/loans/{id}", loanId).with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private static final String APP_BODY = """
        {"loan":{"baseLoanAmount":250000,"downPaymentAmount":50000,"estimatedValue":300000,
                 "city":"Denver","state":"CO","propertyType":"SINGLE_FAMILY","occupancyType":"PRIMARY_RESIDENCE"},
         "borrower":{"firstName":"Patricia","cellPhone":"555-123-4567"}}""";

    // ── borrower self read + write round-trip ────────────────────────────────────────────
    @Test
    void borrowerSavesAndReadsOwnApplication() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myId = addBorrower(loan, "Pat", true);
        linkUser(loan, myId, me);

        mvc.perform(put("/api/loans/{l}/application", loan).with(as(me, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content(APP_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.borrowerId").value(myId))
                .andExpect(jsonPath("$.data.loan.baseLoanAmount").value(250000))
                .andExpect(jsonPath("$.data.borrower.firstName").value("Patricia"));

        // Re-read (independent request) proves persistence across loan + borrower.
        mvc.perform(get("/api/loans/{l}/application", loan).with(as(me, "ROLE_BORROWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loan.city").value("Denver"))
                .andExpect(jsonPath("$.data.loan.downPaymentAmount").value(50000))
                .andExpect(jsonPath("$.data.borrower.firstName").value("Patricia"))
                .andExpect(jsonPath("$.data.borrower.hasSsn").value(false));
    }

    // ── CROWN JEWEL: the §4 subset PUT must not wipe an LO-set staff field ────────────────
    @Test
    void borrowerPutDoesNotWipeLoSetInterestRate() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myId = addBorrower(loan, "Pat", true);
        linkUser(loan, myId, me);
        // LO sets a staff-only field the borrower can never touch.
        patchLoanAsLo(loan, "{\"interestRate\":6.5}");

        mvc.perform(put("/api/loans/{l}/application", loan).with(as(me, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content(APP_BODY))
                .andExpect(status().isOk());

        // Staff read: interestRate PRESERVED (6.5), baseLoanAmount CHANGED by the borrower (250000).
        mvc.perform(get("/api/loans/{id}", loan).with(as(LO_A, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestRate").value(6.5))
                .andExpect(jsonPath("$.data.baseLoanAmount").value(250000));
    }

    // ── denials ──────────────────────────────────────────────────────────────────────────
    @Test
    void borrowerNotOnLoanForbidden() throws Exception {
        String loan = createLoan();
        addBorrower(loan, "Pat", true);   // primary, NOT linked to `me`
        String me = UUID.randomUUID().toString();
        // Resolves to the primary row; assertBorrowerSelfWritable denies (not self, not staff) → 403.
        mvc.perform(put("/api/loans/{l}/application", loan).with(as(me, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content(APP_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void coBorrowerEditsOnlyOwnRow() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        addBorrower(loan, "Pat", true);                 // primary (someone else)
        String myId = addBorrower(loan, "Sam", false);  // the co-borrower caller
        linkUser(loan, myId, me);
        // findSelf resolves the caller's OWN row → write targets it, never the primary → 200.
        mvc.perform(put("/api/loans/{l}/application", loan).with(as(me, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"borrower\":{\"firstName\":\"Samuel\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.borrowerId").value(myId))
                .andExpect(jsonPath("$.data.borrower.firstName").value("Samuel"));
    }

    @Test
    void agentForbiddenAtFilter() throws Exception {
        String loan = createLoan();
        mvc.perform(put("/api/loans/{l}/application", loan)
                        .with(as(UUID.randomUUID().toString(), "ROLE_REAL_ESTATE_AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(APP_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminForbiddenAtFilter() throws Exception {
        String loan = createLoan();
        mvc.perform(get("/api/loans/{l}/application", loan)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedUnauthorized() throws Exception {
        String loan = createLoan();
        mvc.perform(get("/api/loans/{l}/application", loan))
                .andExpect(status().isUnauthorized());
    }

    // ── staff path + tenant isolation ─────────────────────────────────────────────────────
    @Test
    void staffSavesApplicationTargetingPrimary() throws Exception {
        String loan = createLoan();
        addBorrower(loan, "Pat", true);
        // Owning LO uses the same endpoint (no self row) → targets the primary borrower → 200.
        mvc.perform(put("/api/loans/{l}/application", loan).with(as(LO_A, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON).content(APP_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.borrower.firstName").value("Patricia"));
    }

    @Test
    void crossTenantNotFound() throws Exception {
        String loan = createLoan();
        addBorrower(loan, "Pat", true);
        String otherOrg = "00000000-0000-0000-0000-0000000000bb";
        // A PROCESSOR from a DIFFERENT org passes the filter but the loan is invisible (tenant scope) → 404.
        mvc.perform(get("/api/loans/{l}/application", loan)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR", otherOrg)))
                .andExpect(status().isNotFound());
    }

    // ── hardening edges (security review follow-ups) ──────────────────────────────────────
    @Test
    void borrowerWithNonUuidSubjectForbidden() throws Exception {
        String loan = createLoan();
        addBorrower(loan, "Pat", true);
        // Subject not a UUID → currentSubject() null → no self row → not staff → denied at the resolver.
        mvc.perform(put("/api/loans/{l}/application", loan).with(as("not-a-uuid", "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content(APP_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void emptyPutIsNoOpEchoingCurrentState() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myId = addBorrower(loan, "Pat", true);
        linkUser(loan, myId, me);
        // Both blocks null → no writes → 200 echoing the current state (a plausible client call).
        mvc.perform(put("/api/loans/{l}/application", loan).with(as(me, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.borrowerId").value(myId))
                .andExpect(jsonPath("$.data.borrower.firstName").value("Pat"));
    }

    // ── breadth: all 1003 sections persist via the borrower-self write path ───────────────
    @Test
    void borrowerSavesFullApplicationAllSections() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myId = addBorrower(loan, "Pat", true);
        linkUser(loan, myId, me);

        String body = """
            {"income":{
               "employments":[{"employerName":"Acme Corp","employmentStatus":"CURRENT","classification":"PRIMARY","selfEmployed":false,"monthlyIncome":6000}],
               "otherIncome":[{"incomeType":"CHILD_SUPPORT","monthlyAmount":500}]},
             "assets":[{"assetType":"CHECKING","financialInstitution":"BankCo","cashOrMarketValue":12000}],
             "liabilities":[{"liabilityType":"REVOLVING","creditorName":"Visa","monthlyPayment":150,"unpaidBalance":3000}],
             "reo":[{"isSubjectProperty":false,"addressLine1":"1 Rental Rd","city":"Denver","state":"CO","propertyType":"SINGLE_FAMILY","intendedOccupancy":"INVESTMENT","propertyStatus":"RENTAL","marketValue":300000,"grossMonthlyRentalIncome":2000}],
             "declarations":{"occupyAsPrimaryResidence":true,"declaredBankruptcyLast7Years":false},
             "demographics":{"sex":"MALE"}}""";

        mvc.perform(put("/api/loans/{l}/application", loan).with(as(me, "ROLE_BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        RequestPostProcessor who = as(me, "ROLE_BORROWER");
        // Verify each section persisted via the existing borrower-self (T11) GET endpoints.
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/employments", loan, myId).with(who))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].employerName").value("Acme Corp"));
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", loan, myId).with(who))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2)); // BASE (employment) + CHILD_SUPPORT
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/assets", loan, myId).with(who))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].assetType").value("CHECKING"));
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/liabilities", loan, myId).with(who))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].liabilityType").value("REVOLVING"));
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/declarations", loan, myId).with(who))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.occupyAsPrimaryResidence").value(true));
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/demographics", loan, myId).with(who))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sex").value("MALE"));
        // REO is loan-scoped (no borrower-self read) → verify via staff.
        mvc.perform(get("/api/loans/{l}/reo", loan).with(as(LO_A, "ROLE_LO")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].propertyStatus").value("RENTAL"));
    }

    @Test
    void listSectionPutReplacesNotAppends() throws Exception {
        String loan = createLoan();
        String me = UUID.randomUUID().toString();
        String myId = addBorrower(loan, "Pat", true);
        linkUser(loan, myId, me);
        RequestPostProcessor who = as(me, "ROLE_BORROWER");

        mvc.perform(put("/api/loans/{l}/application", loan).with(who).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assets\":[{\"assetType\":\"CHECKING\",\"cashOrMarketValue\":100}]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/loans/{l}/application", loan).with(who).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assets\":[{\"assetType\":\"SAVINGS\",\"cashOrMarketValue\":200}]}"))
                .andExpect(status().isOk());
        // Full-replace (not append) → exactly one asset, the SAVINGS one.
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/assets", loan, myId).with(who))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assetType").value("SAVINGS"));
    }
}
