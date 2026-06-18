package com.msfg.los.origination.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Clone ("Copy to new") IT (Phase 2 T7): deep-copy a loan's applicant tree to a NEW loan via each
 * owning module's service. Asserts reset identifiers (new number, STARTED), dropped SSN, copied
 * borrowers + their income/assets/liabilities + REO, no documents, and the role/tenant gates.
 */
class CloneIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO_A = UUID.randomUUID().toString();
    static final String OTHER_ORG = "00000000-0000-0000-0000-0000000000bb";

    private RequestPostProcessor as(String sub, String role, String org) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", org))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private RequestPostProcessor lo() {
        return as(LO_A, "ROLE_LO", DEFAULT_ORG);
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO_A)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addBorrower(String loanId, String first, String last, boolean primary, String ssn) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"firstName\":\"%s\",\"lastName\":\"%s\",\"primary\":%s," +
                                "\"ssn\":\"%s\",\"maritalStatus\":\"MARRIED\"}")
                                .formatted(first, last, primary, ssn)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Source: property + 2 borrowers (employment/income, asset, liability, a declaration) + an REO. */
    private String seededSource() throws Exception {
        String loanId = createLoan();

        // §4 + property
        mvc.perform(patch("/api/loans/{id}", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Austin\",\"state\":\"TX\",\"addressLine1\":\"1 Main St\"," +
                                "\"estimatedValue\":400000,\"baseLoanAmount\":320000,\"interestRate\":6.5," +
                                "\"loanTermMonths\":360}"))
                .andExpect(status().isOk());

        String b1 = addBorrower(loanId, "Ada", "Lovelace", true, "123-45-6789");
        String b2 = addBorrower(loanId, "Alan", "Turing", false, "222-33-4444");

        // b1: employment + employment-typed income
        var empRes = mvc.perform(post("/api/loans/{l}/borrowers/{b}/employments", loanId, b1).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employerName\":\"Analytical Engines\",\"employmentStatus\":\"CURRENT\"," +
                                "\"classification\":\"PRIMARY\"}"))
                .andExpect(status().isCreated()).andReturn();
        String empId = JsonPath.read(empRes.getResponse().getContentAsString(), "$.data.id");
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/income", loanId, b1).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeType\":\"BASE\",\"monthlyAmount\":8000,\"employmentId\":\"%s\"}"
                                .formatted(empId)))
                .andExpect(status().isCreated());

        // b1: asset + liability
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/assets", loanId, b1).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetType\":\"CHECKING\",\"financialInstitution\":\"First Bank\"," +
                                "\"cashOrMarketValue\":25000}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/liabilities", loanId, b1).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"liabilityType\":\"INSTALLMENT\",\"creditorName\":\"Auto Co\"," +
                                "\"unpaidBalance\":12000,\"monthlyPayment\":350}"))
                .andExpect(status().isCreated());

        // b2: asset (so both borrowers carry some financial data)
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/assets", loanId, b2).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetType\":\"SAVINGS\",\"cashOrMarketValue\":10000}"))
                .andExpect(status().isCreated());

        // b1: declarations (PUT-upsert)
        mvc.perform(put("/api/loans/{l}/borrowers/{b}/declarations", loanId, b1).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occupyAsPrimaryResidence\":true,\"outstandingJudgments\":false}"))
                .andExpect(status().isOk());

        // an REO owned by b1
        mvc.perform(post("/api/loans/{l}/reo", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"ownerBorrowerId\":\"%s\",\"city\":\"Dallas\",\"state\":\"TX\"," +
                                "\"marketValue\":250000,\"mortgageMonthlyPayment\":1500}").formatted(b1)))
                .andExpect(status().isCreated());

        return loanId;
    }

    @Test
    void cloneDeepCopiesTreeAndResetsIdentifiers() throws Exception {
        String sourceId = seededSource();
        String sourceNumber = JsonPath.read(
                mvc.perform(get("/api/loans/{id}", sourceId).with(lo()))
                        .andReturn().getResponse().getContentAsString(),
                "$.data.loanNumber");

        var cloneRes = mvc.perform(post("/api/loans/{id}/clone", sourceId).with(lo()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(notNullValue()))
                .andExpect(jsonPath("$.data.id").value(not(sourceId)))
                .andExpect(jsonPath("$.data.loanNumber").value(notNullValue()))
                .andExpect(jsonPath("$.data.loanNumber").value(not(sourceNumber)))
                .andReturn();
        String newId = JsonPath.read(cloneRes.getResponse().getContentAsString(), "$.data.id");

        // new loan: status reset to STARTED, §4 + property carried
        mvc.perform(get("/api/loans/{id}", newId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STARTED"))
                .andExpect(jsonPath("$.data.propertyCity").value("Austin"))
                .andExpect(jsonPath("$.data.baseLoanAmount").value(320000));

        // both borrowers copied (names match), SSN dropped (no last4/masked) on the clones
        mvc.perform(get("/api/loans/{l}/borrowers", newId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].firstName").value("Ada"))
                .andExpect(jsonPath("$.data[0].lastName").value("Lovelace"))
                .andExpect(jsonPath("$.data[0].primary").value(true))
                .andExpect(jsonPath("$.data[0].ssnLast4").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].ssnMasked").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[1].firstName").value("Alan"))
                .andExpect(jsonPath("$.data[1].ssnLast4").value(org.hamcrest.Matchers.nullValue()));

        // resolve the cloned borrower ids to assert their copied trees
        String clonedBorrowers = mvc.perform(get("/api/loans/{l}/borrowers", newId).with(lo()))
                .andReturn().getResponse().getContentAsString();
        String newB1 = JsonPath.read(clonedBorrowers, "$.data[0].id");
        String newB2 = JsonPath.read(clonedBorrowers, "$.data[1].id");

        // b1 income/assets/liabilities copied (counts match the source: 1 income, 1 asset, 1 liability)
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/income", newId, newB1).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].incomeType").value("BASE"))
                .andExpect(jsonPath("$.data[0].employmentId").value(notNullValue()));
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/employments", newId, newB1).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].employerName").value("Analytical Engines"));
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/assets", newId, newB1).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].cashOrMarketValue").value(25000));
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/liabilities", newId, newB1).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].monthlyPayment").value(350));

        // b2 asset copied
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/assets", newId, newB2).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].cashOrMarketValue").value(10000));

        // b1 declarations copied
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/declarations", newId, newB1).with(lo()))
                .andExpect(jsonPath("$.data.occupyAsPrimaryResidence").value(true));

        // REO copied to the new loan, owner remapped to the cloned b1
        mvc.perform(get("/api/loans/{l}/reo", newId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].city").value("Dallas"))
                .andExpect(jsonPath("$.data[0].ownerBorrowerId").value(newB1));

        // no documents on the new loan
        mvc.perform(get("/api/loans/{l}/documents", newId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0))
                .andExpect(jsonPath("$.data.documents.length()").value(0));

        // source untouched: still has its borrowers + SSN
        mvc.perform(get("/api/loans/{l}/borrowers", sourceId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].ssnLast4").value("6789"));
    }

    @Test
    void processorCanClone() throws Exception {
        String sourceId = seededSource();
        // PROCESSOR has org-wide view → passes the access guard; role gate allows clone.
        mvc.perform(post("/api/loans/{id}/clone", sourceId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_PROCESSOR", DEFAULT_ORG)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(notNullValue()));
    }

    @Test
    void nonOwnerLoCannotClone() throws Exception {
        String sourceId = seededSource();
        // A different LO in the same org is NOT the owner → 403 from the access guard.
        mvc.perform(post("/api/loans/{id}/clone", sourceId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO", DEFAULT_ORG)))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossOrgSourceNotFound() throws Exception {
        String sourceId = seededSource();
        // A cross-org caller cannot see the loan → 404 (tenant-scoped get).
        mvc.perform(post("/api/loans/{id}/clone", sourceId)
                        .with(as(UUID.randomUUID().toString(), "ROLE_LO", OTHER_ORG)))
                .andExpect(status().isNotFound());
    }
}
