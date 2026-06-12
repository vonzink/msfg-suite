package com.msfg.los.aus.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.aus.service.AusVendorPort;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ERROR-audit seam: when the AUS vendor submit throws, the outer transaction rolls back —
 * but the ERROR run row must SURVIVE (REQUIRES_NEW via {@code AusRunErrorRecorder}) and be
 * observable through history (status + errorMessage).
 *
 * <p>This context replaces the deterministic stub with an always-throwing {@link AusVendorPort},
 * so every submit exercises the failure path end to end through the real HTTP + transaction stack.
 */
class AusRunErrorSeamIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class AlwaysFailingVendor {
        @Bean
        @Primary
        AusVendorPort failingAusVendorPort() {
            return submission -> {
                throw new RuntimeException("vendor unavailable (test)");
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private RequestPostProcessor as(String sub, String role) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private RequestPostProcessor admin() {
        return as(UUID.randomUUID().toString(), "ROLE_ADMIN");
    }

    /** Same loan shape AusRunIT uses: complete §4 fields so the AUS file builds cleanly. */
    private String createLoan(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
                .andExpect(status().isCreated()).andReturn();
        String loanId = JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
        mvc.perform(patch("/api/loans/{id}", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"noteAmount":300000,"estimatedValue":400000,"appraisedValue":400000,
                                 "interestRate":6.5,"loanTermMonths":360}"""))
                .andExpect(status().isOk());
        return loanId;
    }

    private void addBorrower(String loSub, String loanId) throws Exception {
        mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Jane\",\"lastName\":\"Doe\",\"primary\":true}"))
                .andExpect(status().isCreated());
    }

    private void putOrgCreds(String vendor) throws Exception {
        mvc.perform(put("/api/org/vendor-credentials/{vendor}", vendor).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u-1\",\"password\":\"p-1\"}"))
                .andExpect(status().isOk());
    }

    /**
     * CROWN JEWEL of the failure path: a vendor submit that throws surfaces as 500, the outer
     * transaction (including the ORDER-mode credit order) rolls back, yet exactly ONE ERROR
     * aus_run row persists with the vendor's message — and history exposes it.
     */
    @Test
    void failedSubmitPersistsErrorRowDespiteOuterRollback() throws Exception {
        putOrgCreds("DU");
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        addBorrower(lo, loanId);
        // No AUS profile: null issueMode defaults to ORDER, so a credit order is minted in the
        // OUTER transaction before the vendor submit throws.

        mvc.perform(post("/api/loans/{loanId}/aus/run", loanId).with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendor\":\"DU\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        // The ERROR audit row survives the rollback (REQUIRES_NEW seam) — this is the assert
        // that proves the old same-transaction save was dead code (0 rows before the fix).
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select status, error_message, vendor_case_id from aus_run where loan_id = ?::uuid",
                loanId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("ERROR");
        assertThat((String) rows.get(0).get("error_message")).contains("vendor unavailable");
        assertThat(rows.get(0).get("vendor_case_id")).isNull();

        // The ORDER-mode credit order is created in the OUTER transaction before submit, so it
        // rolls back WITH the failed run. That is acceptable spec behavior — the credit pull
        // failed along with the run; only the ERROR audit row survives.
        Integer orderRows = jdbc.queryForObject(
                "select count(*) from credit_order where loan_id = ?::uuid", Integer.class, loanId);
        assertThat(orderRows).isEqualTo(0);

        // Observable failure (Finding 3): history exposes the ERROR row with its errorMessage.
        mvc.perform(get("/api/loans/{loanId}/aus/history", loanId).with(as(lo, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].vendor").value("DU"))
                .andExpect(jsonPath("$.data[0].status").value("ERROR"))
                .andExpect(jsonPath("$.data[0].errorMessage", containsString("vendor unavailable")))
                .andExpect(jsonPath("$.data[0].vendorCaseId", nullValue()))
                .andExpect(jsonPath("$.data[0].requestedBy").value(lo));
    }
}
