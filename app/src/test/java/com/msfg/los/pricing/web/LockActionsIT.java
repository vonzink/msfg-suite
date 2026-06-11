package com.msfg.los.pricing.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LockActionsIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    static final String LO = UUID.randomUUID().toString();

    RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG).claim("email", "lo@msfg.test"))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    void patchLoan(String loanId, String jsonBody) throws Exception {
        mvc.perform(patch("/api/loans/{id}", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk());
    }

    /** Standard priceable loan: total 300000, ltv 80.000 (basis = min(375k sales, 380k appraised)), fico 745. */
    String priceableLoan() throws Exception {
        String id = createLoan();
        patchLoan(id, """
                {
                  "baseLoanAmount": 300000,
                  "interestRate": 6.5,
                  "loanTermMonths": 360,
                  "qualifyingCreditScore": 745,
                  "salesPrice": 375000,
                  "appraisedValue": 380000,
                  "amortizationType": "FIXED"
                }
                """);
        return id;
    }

    String cypBody() {
        return """
                {"rate": 6.5, "commitmentDays": 30, "compensationPayerType": "LENDER_PAID"}
                """;
    }

    @Test
    void extend_addsDays_appendsExtensionFeeRow_resumsFinal() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());

        String expBefore = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(get("/api/loans/{id}/pricing", loanId).with(lo())).andReturn()
                        .getResponse().getContentAsString(), "$.data.currentExpiration");

        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"additionalDays\": 15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockStatus").value("LOCKED"))
                .andExpect(jsonPath("$.data.extensionDaysTotal").value(15))
                .andExpect(jsonPath("$.data.currentExpiration")
                        .value(java.time.LocalDate.parse(expBefore).plusDays(15).toString()));

        // 7 rows now; Extension Fee 15*0.020=0.300 => $900.00 ; Final 0.425 => $1275.00 ; FAC 1.425 => $4275.00
        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data[3].name").value("Extension Fee (15 days)"))
                .andExpect(jsonPath("$.data[3].adjustmentPercent").value(0.300))
                .andExpect(jsonPath("$.data[3].dollarAmount").value(900.00))
                .andExpect(jsonPath("$.data[4].adjustmentPercent").value(0.425))
                .andExpect(jsonPath("$.data[4].dollarAmount").value(1275.00))
                .andExpect(jsonPath("$.data[6].dollarAmount").value(4275.00));

        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].action").value("EXTEND"));
    }

    @Test
    void rateChange_requotes_keepsExpirationAndLockDate() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());

        String before = mvc.perform(get("/api/loans/{id}/pricing", loanId).with(lo()))
                .andReturn().getResponse().getContentAsString();
        String expBefore = com.jayway.jsonpath.JsonPath.read(before, "$.data.currentExpiration");
        String lockDateBefore = com.jayway.jsonpath.JsonPath.read(before, "$.data.lockDate");

        // 7.0 @ 30d: Base = -((7-7)*0.5) - 0.125 = -0.125 => -$375.00 ; Final 0.375 ; FAC 1.375
        mvc.perform(post("/api/loans/{id}/pricing/lock/rate-change", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 7.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interestRate").value(7.0))
                .andExpect(jsonPath("$.data.currentExpiration").value(expBefore))
                .andExpect(jsonPath("$.data.lockDate").value(lockDateBefore));

        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(jsonPath("$.data[0].adjustmentPercent").value(-0.125))
                .andExpect(jsonPath("$.data[0].dollarAmount").value(-375.00))
                .andExpect(jsonPath("$.data[3].adjustmentPercent").value(0.375));

        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].action").value("RATE_CHANGE"));
    }

    @Test
    void extend_withoutLock_409() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"additionalDays\": 15}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_STATE_CONFLICT"));
    }

    @Test
    void rateChange_withoutLock_409() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/rate-change", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rate\": 7.0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_STATE_CONFLICT"));
    }

    @Test
    void extend_invalidAdditionalDays_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());
        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"additionalDays\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.additionalDays").exists());
    }

    /** Force the lock past expiration with raw SQL (bypasses Hibernate/@TenantId; owner datasource so RLS doesn't block). */
    void forceExpired(String loanId) {
        jdbc.update("update rate_lock set expiration_date = current_date - 1 where loan_id = ?::uuid", loanId);
    }

    @Test
    void expiredLock_readsAsExpired_andRelockResetsIt() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());
        // extend so extensionDaysTotal is nonzero, then expire
        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content("{\"additionalDays\": 15}")).andExpect(status().isOk());
        forceExpired(loanId);

        mvc.perform(get("/api/loans/{id}/pricing", loanId).with(lo()))
                .andExpect(jsonPath("$.data.lockStatus").value("EXPIRED"));

        mvc.perform(post("/api/loans/{id}/pricing/lock/relock", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rate\": 6.75, \"commitmentDays\": 15, \"compensationPayerType\": \"LENDER_PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockStatus").value("LOCKED"))
                .andExpect(jsonPath("$.data.interestRate").value(6.75))
                .andExpect(jsonPath("$.data.commitmentDays").value(15))
                .andExpect(jsonPath("$.data.extensionDaysTotal").value(0));

        // extension row gone after relock (6 rows again)
        mvc.perform(get("/api/loans/{id}/pricing/adjustments", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(6));

        mvc.perform(get("/api/loans/{id}/pricing/lock/history", loanId).with(lo()))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[2].action").value("RELOCK"));
    }

    @Test
    void relock_onActiveLock_409() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());
        mvc.perform(post("/api/loans/{id}/pricing/lock/relock", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_STATE_CONFLICT"));
    }

    @Test
    void relock_neverLocked_409() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/relock", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_STATE_CONFLICT"));
    }

    @Test
    void expiredLock_blocksExtendRateChangeAndCyp() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());
        forceExpired(loanId);

        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"additionalDays\": 15}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/loans/{id}/pricing/lock/rate-change", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rate\": 7.0}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(cypBody()))
                .andExpect(status().isConflict());
    }

    @Test
    void extend_additionalDaysAboveMax_400() throws Exception {
        String loanId = priceableLoan();
        mvc.perform(post("/api/loans/{id}/pricing/lock/control-your-price", loanId).with(lo())
                .contentType(MediaType.APPLICATION_JSON).content(cypBody())).andExpect(status().isOk());
        mvc.perform(post("/api/loans/{id}/pricing/lock/extend", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"additionalDays\": 61}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.additionalDays").exists());
    }
}
