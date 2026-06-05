package com.msfg.los.parties.web;
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

class BorrowerPiiIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    static final String LO = UUID.randomUUID().toString();
    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG)).authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }
    private String createLoan() throws Exception {
        var r = mvc.perform(post("/api/loans").with(lo()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
            .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.data.id");
    }
    private java.util.Map.Entry<String,String> addBorrowerWithSsn(String loanId, String ssn) throws Exception {
        var r = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Abbas\",\"lastName\":\"Hussein\",\"primary\":true,\"ssn\":\"%s\"}".formatted(ssn)))
            .andExpect(status().isCreated()).andReturn();
        String body = r.getResponse().getContentAsString();
        return java.util.Map.entry((String) com.jayway.jsonpath.JsonPath.read(body, "$.data.id"), body);
    }

    @Test void ssnIsCiphertextAtRest_andMaskedInResponse() throws Exception {
        String loanId = createLoan();
        var e = addBorrowerWithSsn(loanId, "123-45-6789");
        String borrowerId = e.getKey(), body = e.getValue();
        // response: masked, never the full SSN
        assertThat(body).contains("\"ssnLast4\":\"6789\"").doesNotContain("123456789").doesNotContain("123-45-6789");
        // raw DB column is ciphertext (JDBC as superuser bypasses RLS to read the row)
        String raw = jdbc.queryForObject("select ssn from borrower_party where id = ?::uuid", String.class, borrowerId);
        assertThat(raw).isNotNull().isNotEqualTo("123-45-6789").doesNotContain("123456789");
    }
    @Test void revealReturnsFullSsnAndWritesAudit() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrowerWithSsn(loanId, "123-45-6789").getKey();
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/reveal-ssn", loanId, borrowerId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"underwriting review\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ssn").value("123-45-6789"));
        Integer audits = jdbc.queryForObject(
            "select count(*) from pii_access_log where subject_id = ?::uuid and field = 'SSN'", Integer.class, borrowerId);
        assertThat(audits).isEqualTo(1);
    }
    @Test void revealRequiresReason400() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrowerWithSsn(loanId, "123-45-6789").getKey();
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/reveal-ssn", loanId, borrowerId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }
    @Test void invalidSsnRejected400() throws Exception {
        String loanId = createLoan();
        mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"A\",\"lastName\":\"B\",\"primary\":true,\"ssn\":\"000-12-3456\"}"))
            .andExpect(status().isBadRequest());
    }
}
