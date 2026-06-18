package com.msfg.los.loan.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 2 T3 — status backdating, soft-delete, lookup-by-number, typeahead search.
 */
class LoanCoreEnhancementsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static final String LO_A = UUID.randomUUID().toString();
    static final String LO_B = UUID.randomUUID().toString();

    private RequestPostProcessor lo(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private RequestPostProcessor role(String sub, String role) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority(role));
    }

    private RequestPostProcessor crossOrg() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                               .claim("org_id", "ffffffff-ffff-ffff-ffff-ffffffffffff"))
                   .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private String createLoan(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo(loSub))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
            .andExpect(status().isCreated())
            .andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String loanNumber(String id) {
        return jdbc.queryForObject("select loan_number from loan where id = ?::uuid", String.class, id);
    }

    private void addPrimaryBorrower(String loanId, String first, String last) throws Exception {
        mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo(LO_A))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"%s\",\"lastName\":\"%s\",\"primary\":true}".formatted(first, last)))
            .andExpect(status().isCreated());
    }

    // ---- status backdating ----------------------------------------------------------------

    @Test
    void transitionWithoutTransitionedAt_stampsNow_andIsBackwardCompatible() throws Exception {
        String id = createLoan(LO_A);
        Instant before = Instant.now().minusSeconds(5);
        mvc.perform(post("/api/loans/{id}/status", id).with(lo(LO_A))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"APPLICATION_IN_PROGRESS\",\"reason\":\"go\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("APPLICATION_IN_PROGRESS"));

        Instant changed = jdbc.queryForObject(
                "select status_changed_at from loan where id = ?::uuid", Instant.class, id);
        assertThat(changed).isAfter(before);
        Instant hist = jdbc.queryForObject(
                "select transitioned_at from loan_status_history where loan_id = ?::uuid", Instant.class, id);
        assertThat(hist).isAfter(before);
    }

    @Test
    void transitionWithTransitionedAt_backdatesHistoryAndMirror() throws Exception {
        String id = createLoan(LO_A);
        Instant backdated = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        mvc.perform(post("/api/loans/{id}/status", id).with(lo(LO_A))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"APPLICATION_IN_PROGRESS\",\"reason\":\"backdate\",\"transitionedAt\":\""
                        + backdated + "\"}"))
            .andExpect(status().isOk());

        Instant changed = jdbc.queryForObject(
                "select status_changed_at from loan where id = ?::uuid", Instant.class, id);
        Instant hist = jdbc.queryForObject(
                "select transitioned_at from loan_status_history where loan_id = ?::uuid", Instant.class, id);
        assertThat(changed).isEqualTo(backdated);
        assertThat(hist).isEqualTo(backdated);
    }

    // ---- soft-delete ----------------------------------------------------------------------

    @Test
    void softDelete_thenGetIs404_andDisappearsFromPipeline() throws Exception {
        String id = createLoan(LO_A);
        mvc.perform(delete("/api/loans/{id}", id).with(lo(LO_A))).andExpect(status().isOk());

        mvc.perform(get("/api/loans/{id}", id).with(lo(LO_A))).andExpect(status().isNotFound());

        String resp = mvc.perform(get("/api/loans").with(lo(LO_A)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> ids = com.jayway.jsonpath.JsonPath.parse(resp).read("$.data.items[*].id");
        assertThat(ids).doesNotContain(id);
    }

    @Test
    void softDelete_isIdempotent_secondCallIs404() throws Exception {
        String id = createLoan(LO_A);
        mvc.perform(delete("/api/loans/{id}", id).with(lo(LO_A))).andExpect(status().isOk());
        mvc.perform(delete("/api/loans/{id}", id).with(lo(LO_A))).andExpect(status().isNotFound());
    }

    @Test
    void softDelete_processorIsForbidden403() throws Exception {
        String id = createLoan(LO_A);
        mvc.perform(delete("/api/loans/{id}", id)
                .with(role(UUID.randomUUID().toString(), "ROLE_PROCESSOR")))
            .andExpect(status().isForbidden());
        // and the loan is still live
        mvc.perform(get("/api/loans/{id}", id).with(lo(LO_A))).andExpect(status().isOk());
    }

    @Test
    void softDelete_otherLoCannotDelete403() throws Exception {
        String id = createLoan(LO_A);
        mvc.perform(delete("/api/loans/{id}", id).with(lo(LO_B)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/loans/{id}", id).with(lo(LO_A))).andExpect(status().isOk());
    }

    @Test
    void softDelete_adminCanDelete() throws Exception {
        String id = createLoan(LO_A);
        mvc.perform(delete("/api/loans/{id}", id).with(role(UUID.randomUUID().toString(), "ROLE_ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(get("/api/loans/{id}", id).with(lo(LO_A))).andExpect(status().isNotFound());
    }

    // ---- lookup by number -----------------------------------------------------------------

    @Test
    void lookupByNumber_returnsSummary() throws Exception {
        String id = createLoan(LO_A);
        String num = loanNumber(id);
        mvc.perform(get("/api/loans/number/{num}", num).with(lo(LO_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.loanNumber").value(num));
    }

    @Test
    void lookupByNumber_crossOrgIs404() throws Exception {
        String id = createLoan(LO_A);
        String num = loanNumber(id);
        mvc.perform(get("/api/loans/number/{num}", num).with(crossOrg()))
            .andExpect(status().isNotFound());
    }

    @Test
    void lookupByNumber_softDeletedIs404() throws Exception {
        String id = createLoan(LO_A);
        String num = loanNumber(id);
        mvc.perform(delete("/api/loans/{id}", id).with(lo(LO_A))).andExpect(status().isOk());
        mvc.perform(get("/api/loans/number/{num}", num).with(lo(LO_A)))
            .andExpect(status().isNotFound());
    }

    @Test
    void lookupByNumber_otherLoForbidden403() throws Exception {
        String id = createLoan(LO_A);
        String num = loanNumber(id);
        mvc.perform(get("/api/loans/number/{num}", num).with(lo(LO_B)))
            .andExpect(status().isForbidden());
    }

    // ---- typeahead search -----------------------------------------------------------------

    @Test
    void search_shortQueryReturnsEmpty() throws Exception {
        mvc.perform(get("/api/loans/search").param("q", "a").with(lo(LO_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void search_byLoanNumberExact_returnsHit() throws Exception {
        String id = createLoan(LO_A);
        String num = loanNumber(id);
        mvc.perform(get("/api/loans/search").param("q", num).with(lo(LO_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(id))
            .andExpect(jsonPath("$.data[0].loanNumber").value(num));
    }

    @Test
    void search_byLoanNumberPrefix_returnsHit() throws Exception {
        String id = createLoan(LO_A);
        String num = loanNumber(id);
        String prefix = num.substring(0, Math.max(2, num.length() - 1));
        mvc.perform(get("/api/loans/search").param("q", prefix).with(lo(LO_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id", hasItem(id)));
    }

    @Test
    void search_byBorrowerName_returnsHitWithName() throws Exception {
        String id = createLoan(LO_A);
        addPrimaryBorrower(id, "Zaphod", "Beeblebrox");
        mvc.perform(get("/api/loans/search").param("q", "beeble").with(lo(LO_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id", hasItem(id)))
            .andExpect(jsonPath("$.data[?(@.id=='" + id + "')].borrowerName",
                    hasItem("Zaphod Beeblebrox")));
    }

    @Test
    void search_scopedToOwningLo_excludesOthersLoans() throws Exception {
        String idA = createLoan(LO_A);
        addPrimaryBorrower(idA, "Quux", "Sn" + UUID.randomUUID().toString().substring(0, 6));
        // LO_B searching for LO_A's distinctly-named borrower sees nothing of LO_A's loan.
        mvc.perform(get("/api/loans/search").param("q", "quux").with(lo(LO_B)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id", not(hasItem(idA))));
    }

    @Test
    void search_softDeletedExcluded() throws Exception {
        String id = createLoan(LO_A);
        String num = loanNumber(id);
        mvc.perform(delete("/api/loans/{id}", id).with(lo(LO_A))).andExpect(status().isOk());
        mvc.perform(get("/api/loans/search").param("q", num).with(lo(LO_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].id", not(hasItem(id))));
    }

    @Test
    void search_limitIsCapped() throws Exception {
        // Cap is 50; with limit=999 the request still succeeds (capped server-side, no 400).
        mvc.perform(get("/api/loans/search").param("q", "LN").param("limit", "999").with(lo(LO_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(lessThanOrEqualTo(50))));
    }
}
