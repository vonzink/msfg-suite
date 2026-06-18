package com.msfg.los.loan.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 2 T4 — full pipeline filter set + sort, query-side via a JPA {@code Specification}.
 *
 * <p>Each test asserts ONE facet filters at the query layer (the matching loan is present, a
 * deliberately-non-matching loan is absent), plus default newest-first ordering and the sort
 * whitelist. A MANAGER (org-wide view) is used for filter tests so caller-scope does not mask
 * the assertions; a separate test pins LO owner-scoping is preserved as a base predicate.
 */
class LoanPipelineFilterIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static final String LO_A = UUID.randomUUID().toString();
    static final String LO_B = UUID.randomUUID().toString();
    static final String MGR = UUID.randomUUID().toString();

    private RequestPostProcessor lo(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private RequestPostProcessor mgr() {
        return jwt().jwt(j -> j.subject(MGR).claim("org_id", DEFAULT_ORG))
                   .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private String createLoan(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo(loSub))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
            .andExpect(status().isCreated())
            .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private List<String> ids(String url, RequestPostProcessor who, Object... params) throws Exception {
        var req = get(url).with(who);
        for (int i = 0; i + 1 < params.length; i += 2) req = req.param((String) params[i], String.valueOf(params[i + 1]));
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.data.items[*].id");
    }

    // direct-SQL setters (no public endpoint covers these columns succinctly in setup)
    private void setMortgageType(String id, String t) {
        jdbc.update("update loan set mortgage_type=? where id=?::uuid", t, id);
    }
    private void setBaseLoanAmount(String id, String amt) {
        jdbc.update("update loan set base_loan_amount=?::numeric where id=?::uuid", amt, id);
    }
    private void setNoteAmount(String id, String amt) {
        jdbc.update("update loan set note_amount=?::numeric where id=?::uuid", amt, id);
    }
    private void setConsummationDate(String id, LocalDate d) {
        jdbc.update("update loan set consummation_date=? where id=?::uuid", d, id);
    }
    private void setStatusChangedAt(String id, Instant ts) {
        jdbc.update("update loan set status_changed_at=? where id=?::uuid", java.sql.Timestamp.from(ts), id);
    }
    private void setStatus(String id, String s) {
        jdbc.update("update loan set status=? where id=?::uuid", s, id);
    }
    private void setCreatedAt(String id, Instant ts) {
        jdbc.update("update loan set created_at=? where id=?::uuid", java.sql.Timestamp.from(ts), id);
    }

    private String addOutstandingConditions(String loanId, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            mvc.perform(post("/api/loans/{id}/conditions", loanId).with(mgr())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"conditionText\":\"need doc " + i + "\",\"status\":\"Outstanding\"}"))
                .andExpect(status().isCreated());
        }
        return loanId;
    }

    // ---- status (List) --------------------------------------------------------------------

    @Test
    void statusFilter_singleValue_matchesExisting() throws Exception {
        String inUw = createLoan(LO_A);  setStatus(inUw, "IN_UNDERWRITING");
        String started = createLoan(LO_A); // stays STARTED
        var got = ids("/api/loans", mgr(), "status", "IN_UNDERWRITING");
        assertThat(got).contains(inUw).doesNotContain(started);
    }

    @Test
    void statusFilter_multiValue_unionMatches() throws Exception {
        String a = createLoan(LO_A); setStatus(a, "SUBMITTED");
        String b = createLoan(LO_A); setStatus(b, "CLOSING");
        String c = createLoan(LO_A); setStatus(c, "DENIED");
        var got = ids("/api/loans", mgr(), "status", "SUBMITTED", "status", "CLOSING");
        assertThat(got).contains(a, b).doesNotContain(c);
    }

    // ---- lo (assigned LO) -----------------------------------------------------------------

    @Test
    void loFilter_restrictsToOfficer() throws Exception {
        String mine = createLoan(LO_A);
        String theirs = createLoan(LO_B);
        var got = ids("/api/loans", mgr(), "lo", LO_A);
        assertThat(got).contains(mine).doesNotContain(theirs);
    }

    // ---- conditionsGt (cross-module via conditions service) --------------------------------

    @Test
    void conditionsGtFilter_keepsLoansOverThreshold() throws Exception {
        String many = createLoan(LO_A); addOutstandingConditions(many, 3);
        String few = createLoan(LO_A);  addOutstandingConditions(few, 1);
        String none = createLoan(LO_A);
        var got = ids("/api/loans", mgr(), "conditionsGt", "2");
        assertThat(got).contains(many).doesNotContain(few, none);
    }

    @Test
    void conditionsGtFilter_emptyResultWhenNoneQualify() throws Exception {
        String few = createLoan(LO_A); addOutstandingConditions(few, 1);
        var got = ids("/api/loans", mgr(), "conditionsGt", "99");
        assertThat(got).doesNotContain(few);
    }

    // ---- closingFrom / closingTo (consummationDate) ---------------------------------------

    @Test
    void closingDateRange_filtersByConsummationDate() throws Exception {
        String inWin = createLoan(LO_A);  setConsummationDate(inWin, LocalDate.now().plusDays(10));
        String tooFar = createLoan(LO_A); setConsummationDate(tooFar, LocalDate.now().plusDays(60));
        String nullDate = createLoan(LO_A);
        var got = ids("/api/loans", mgr(),
                "closingFrom", LocalDate.now().toString(),
                "closingTo", LocalDate.now().plusDays(30).toString());
        assertThat(got).contains(inWin).doesNotContain(tooFar, nullDate);
    }

    // ---- stageAgeGt (status_changed_at older than N days) ---------------------------------

    @Test
    void stageAgeGtFilter_keepsStaleLoans() throws Exception {
        String stale = createLoan(LO_A);  setStatusChangedAt(stale, Instant.now().minus(40, ChronoUnit.DAYS));
        String fresh = createLoan(LO_A);  setStatusChangedAt(fresh, Instant.now().minus(2, ChronoUnit.DAYS));
        String nullStage = createLoan(LO_A); // status_changed_at null → not stale
        var got = ids("/api/loans", mgr(), "stageAgeGt", "30");
        assertThat(got).contains(stale).doesNotContain(fresh, nullStage);
    }

    // ---- loanType (List vs mortgageType) --------------------------------------------------

    @Test
    void loanTypeFilter_multiValueMatchesMortgageType() throws Exception {
        String fha = createLoan(LO_A); setMortgageType(fha, "FHA");
        String va = createLoan(LO_A);  setMortgageType(va, "VA");
        String conv = createLoan(LO_A); setMortgageType(conv, "CONVENTIONAL");
        var got = ids("/api/loans", mgr(), "loanType", "FHA", "loanType", "VA");
        assertThat(got).contains(fha, va).doesNotContain(conv);
    }

    // ---- amountMin / amountMax (baseLoanAmount, fallback noteAmount) -----------------------

    @Test
    void amountRange_filtersByBaseLoanAmount() throws Exception {
        String mid = createLoan(LO_A);  setBaseLoanAmount(mid, "300000");
        String low = createLoan(LO_A);  setBaseLoanAmount(low, "100000");
        String high = createLoan(LO_A); setBaseLoanAmount(high, "900000");
        var got = ids("/api/loans", mgr(), "amountMin", "250000", "amountMax", "500000");
        assertThat(got).contains(mid).doesNotContain(low, high);
    }

    @Test
    void amountRange_fallsBackToNoteAmountWhenBaseNull() throws Exception {
        String onNote = createLoan(LO_A); setNoteAmount(onNote, "275000"); // base stays null
        String outside = createLoan(LO_A); setNoteAmount(outside, "50000");
        var got = ids("/api/loans", mgr(), "amountMin", "200000", "amountMax", "400000");
        assertThat(got).contains(onNote).doesNotContain(outside);
    }

    // ---- default ordering + sort whitelist ------------------------------------------------

    @Test
    void defaultOrdering_isNewestFirstByCreatedAt() throws Exception {
        // Isolate this test's two loans by a unique amount window — the shared test DB accumulates
        // loans across the class, so a global page-0 assertion would be order-of-execution-fragile.
        String older = createLoan(LO_A); setBaseLoanAmount(older, "611000"); setCreatedAt(older, Instant.now().minus(10, ChronoUnit.DAYS));
        String newer = createLoan(LO_A); setBaseLoanAmount(newer, "612000"); setCreatedAt(newer, Instant.now().minus(1, ChronoUnit.DAYS));
        var got = ids("/api/loans", mgr(), "amountMin", "610000", "amountMax", "613000");
        assertThat(got).contains(older, newer);
        assertThat(got.indexOf(newer)).isLessThan(got.indexOf(older));
    }

    @Test
    void sortByAmountAsc_ordersAscending() throws Exception {
        // Narrow, unique amount window so both loans land on page 0 regardless of shared-DB volume.
        String big = createLoan(LO_A);   setBaseLoanAmount(big, "631000");
        String small = createLoan(LO_A); setBaseLoanAmount(small, "630000");
        var got = ids("/api/loans", mgr(), "amountMin", "629000", "amountMax", "632000",
                "sort", "amount,asc");
        assertThat(got).contains(small, big);
        assertThat(got.indexOf(small)).isLessThan(got.indexOf(big));
    }

    @Test
    void sortInjectionSafe_unknownFieldFallsBackToDefault() throws Exception {
        // Isolate via a unique amount window (shared test DB) so the assertion is deterministic.
        String a = createLoan(LO_A); setBaseLoanAmount(a, "621000"); setCreatedAt(a, Instant.now().minus(5, ChronoUnit.DAYS));
        String b = createLoan(LO_A); setBaseLoanAmount(b, "622000"); setCreatedAt(b, Instant.now().minus(1, ChronoUnit.DAYS));
        // garbage sort must not 500/error and must apply the default newest-first (createdAt DESC)
        var got = ids("/api/loans", mgr(),
                "amountMin", "620000", "amountMax", "623000",
                "sort", "name); drop table loan;--,asc");
        assertThat(got).contains(a, b);
        assertThat(got.indexOf(b)).isLessThan(got.indexOf(a));
    }

    // ---- caller-scope preserved -----------------------------------------------------------

    @Test
    void ownerScope_loSeesOnlyOwnLoans_evenWithFilters() throws Exception {
        String mine = createLoan(LO_A); setMortgageType(mine, "FHA");
        String theirs = createLoan(LO_B); setMortgageType(theirs, "FHA");
        // LO_A is owner-scoped: filtering by FHA still excludes LO_B's FHA loan.
        var got = ids("/api/loans", lo(LO_A), "loanType", "FHA");
        assertThat(got).contains(mine).doesNotContain(theirs);
    }

    // ---- combined facets ------------------------------------------------------------------

    @Test
    void combinedFacets_intersect() throws Exception {
        String hit = createLoan(LO_A);
        setMortgageType(hit, "FHA"); setBaseLoanAmount(hit, "350000"); setStatus(hit, "IN_UNDERWRITING");
        String wrongType = createLoan(LO_A);
        setMortgageType(wrongType, "VA"); setBaseLoanAmount(wrongType, "350000"); setStatus(wrongType, "IN_UNDERWRITING");
        var got = ids("/api/loans", mgr(),
                "loanType", "FHA", "amountMin", "300000", "status", "IN_UNDERWRITING");
        assertThat(got).contains(hit).doesNotContain(wrongType);
    }
}
