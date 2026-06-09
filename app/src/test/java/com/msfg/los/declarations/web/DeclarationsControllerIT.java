package com.msfg.los.declarations.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeclarationsControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addBorrower(String loanId) throws Exception {
        var res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"primary\":true}"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // ── PUT booleans (true/false/omitted=null) + bankruptcyTypes round-trip ──
    @Test
    void putAndGetRoundTrips() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        // PUT: true, false, omitted (null), + bankruptcyTypes set
        mvc.perform(put("/api/loans/{l}/borrowers/{b}/declarations", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "occupyAsPrimaryResidence": true,
                                  "outstandingJudgments": false,
                                  "declaredBankruptcyLast7Years": true,
                                  "bankruptcyTypes": ["CHAPTER_7", "CHAPTER_13"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.occupyAsPrimaryResidence").value(true))
                .andExpect(jsonPath("$.data.outstandingJudgments").value(false))
                .andExpect(jsonPath("$.data.declaredBankruptcyLast7Years").value(true))
                .andExpect(jsonPath("$.data.bankruptcyTypes[0]").value("CHAPTER_7"))
                .andExpect(jsonPath("$.data.bankruptcyTypes[1]").value("CHAPTER_13"));

        // GET round-trips same values
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/declarations", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                // true stays true
                .andExpect(jsonPath("$.data.occupyAsPrimaryResidence").value(true))
                // false stays false
                .andExpect(jsonPath("$.data.outstandingJudgments").value(false))
                // omitted field (hadOwnershipInterestLast3Years) stays null
                .andExpect(jsonPath("$.data.hadOwnershipInterestLast3Years").doesNotExist())
                // bankruptcyTypes preserved
                .andExpect(jsonPath("$.data.bankruptcyTypes[0]").value("CHAPTER_7"))
                .andExpect(jsonPath("$.data.bankruptcyTypes[1]").value("CHAPTER_13"));
    }

    // ── Second PUT replaces all fields ───────────────────────────────────────
    @Test
    void secondPutReplaces() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(put("/api/loans/{l}/borrowers/{b}/declarations", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occupyAsPrimaryResidence\":true,\"bankruptcyTypes\":[\"CHAPTER_7\"]}"))
                .andExpect(status().isOk());

        // second PUT with different values
        mvc.perform(put("/api/loans/{l}/borrowers/{b}/declarations", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occupyAsPrimaryResidence\":false,\"bankruptcyTypes\":[\"CHAPTER_13\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.occupyAsPrimaryResidence").value(false))
                .andExpect(jsonPath("$.data.bankruptcyTypes[0]").value("CHAPTER_13"))
                .andExpect(jsonPath("$.data.bankruptcyTypes.length()").value(1));

        // GET reflects replacement
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/declarations", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.occupyAsPrimaryResidence").value(false))
                .andExpect(jsonPath("$.data.bankruptcyTypes[0]").value("CHAPTER_13"));

        // exactly one row — upsert, not duplicate insert
        Integer count = jdbc.queryForObject(
                "select count(*) from borrower_declarations where borrower_id = ?::uuid",
                Integer.class, borrowerId);
        assertThat(count).isEqualTo(1);
    }

    // ── GET before any PUT returns 200 with empty/default body ──────────────
    @Test
    void getBeforePutReturnsEmpty() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(get("/api/loans/{l}/borrowers/{b}/declarations", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hadOwnershipInterestLast3Years").doesNotExist())
                .andExpect(jsonPath("$.data.bankruptcyTypes").isEmpty());
    }

    // ── Cross-org JWT → 404 ──────────────────────────────────────────────────
    @Test
    void crossOrgReturns404() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        var userB = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(put("/api/loans/{l}/borrowers/{b}/declarations", loanId, borrowerId).with(userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occupyAsPrimaryResidence\":true}"))
                .andExpect(status().isNotFound());
    }

    // ── No token → 401 ──────────────────────────────────────────────────────
    @Test
    void noTokenReturns401() throws Exception {
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/declarations",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
