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

class DemographicsControllerIT extends AbstractIntegrationTest {

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
                        .content("{\"firstName\":\"Abbas\",\"lastName\":\"Hussein\",\"primary\":true}"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // ── GET before any PUT returns 200 with empty sets ──────────────────────
    @Test
    void getBeforePutReturnsEmpty() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(get("/api/loans/{l}/borrowers/{b}/demographics", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ethnicity").isArray())
                .andExpect(jsonPath("$.data.ethnicity").isEmpty())
                .andExpect(jsonPath("$.data.race").isArray())
                .andExpect(jsonPath("$.data.race").isEmpty());
    }

    // ── PUT then GET round-trips sets + JDBC converter-string assertion ──────
    @Test
    void putThenGetRoundTrips() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(put("/api/loans/{l}/borrowers/{b}/demographics", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ethnicity":["HISPANIC_OR_LATINO","MEXICAN"],
                                 "race":["ASIAN","CHINESE","WHITE"],
                                 "sex":"MALE",
                                 "applicationTakenMethod":"INTERNET"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sex").value("MALE"))
                .andExpect(jsonPath("$.data.ethnicity[0]").value("HISPANIC_OR_LATINO"))
                .andExpect(jsonPath("$.data.ethnicity[1]").value("MEXICAN"))
                .andExpect(jsonPath("$.data.race[0]").value("ASIAN"));

        // GET returns same sets
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/demographics", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ethnicity[0]").value("HISPANIC_OR_LATINO"))
                .andExpect(jsonPath("$.data.ethnicity[1]").value("MEXICAN"))
                .andExpect(jsonPath("$.data.race[0]").value("ASIAN"))
                .andExpect(jsonPath("$.data.race[1]").value("CHINESE"))
                .andExpect(jsonPath("$.data.race[2]").value("WHITE"))
                .andExpect(jsonPath("$.data.sex").value("MALE"))
                .andExpect(jsonPath("$.data.applicationTakenMethod").value("INTERNET"));

        // JDBC assert: converter stored comma-joined string
        String ethDb = jdbc.queryForObject(
                "select ethnicity from borrower_demographics where borrower_id = ?::uuid",
                String.class, borrowerId);
        assertThat(ethDb).isEqualTo("HISPANIC_OR_LATINO,MEXICAN");
    }

    // ── Second PUT replaces (upsert, not duplicate) ──────────────────────────
    @Test
    void secondPutReplacesNotDuplicates() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        // first PUT
        mvc.perform(put("/api/loans/{l}/borrowers/{b}/demographics", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ethnicity\":[\"HISPANIC_OR_LATINO\"],\"race\":[\"WHITE\"],\"sex\":\"MALE\"}"))
                .andExpect(status().isOk());

        // second PUT with different values
        mvc.perform(put("/api/loans/{l}/borrowers/{b}/demographics", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ethnicity\":[\"NOT_HISPANIC_OR_LATINO\"],\"race\":[\"BLACK_OR_AFRICAN_AMERICAN\"],\"sex\":\"FEMALE\"}"))
                .andExpect(status().isOk());

        // GET reflects replacement
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/demographics", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ethnicity[0]").value("NOT_HISPANIC_OR_LATINO"))
                .andExpect(jsonPath("$.data.sex").value("FEMALE"));

        // exactly one row — upsert, not duplicate insert
        Integer count = jdbc.queryForObject(
                "select count(*) from borrower_demographics where borrower_id = ?::uuid",
                Integer.class, borrowerId);
        assertThat(count).isEqualTo(1);
    }

    // ── Cross-org JWT → 404 ──────────────────────────────────────────────────
    @Test
    void crossOrgReturns404() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        var userB = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(put("/api/loans/{l}/borrowers/{b}/demographics", loanId, borrowerId).with(userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ethnicity\":[\"HISPANIC_OR_LATINO\"]}"))
                .andExpect(status().isNotFound());
    }

    // ── No token → 401 ──────────────────────────────────────────────────────
    @Test
    void noTokenReturns401() throws Exception {
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/demographics",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
