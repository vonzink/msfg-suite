package com.msfg.los.disclosures.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.disclosures.domain.BusinessDayType;
import com.msfg.los.disclosures.timing.BusinessDayCalculator;
import com.msfg.los.disclosures.timing.GeneralBusinessDayConfig;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Disclosures Task 11 — revised-LE 3-day clock. A valid changed circumstance (an ACCEPTED CoC)
 * starts a 3 GENERAL business-day deadline to deliver a revised Loan Estimate
 * (1026.19(e)(3)(iv)/(e)(4)). The deadline runs from the CoC decision date and surfaces on the
 * loan-level timing rollup as {@code revisedLeDeadline}. A loan with no accepted CoC yields null.
 */
class RevisedLeClockIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    private final BusinessDayCalculator calc = new BusinessDayCalculator();

    private RequestPostProcessor lo(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private RequestPostProcessor underwriter(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"));
    }

    private String createLoan(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo(loSub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String submitCoc(String loSub, String loanId) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/coc/submit", loanId).with(lo(loSub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"BORROWER_REQUESTED\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private void acceptCoc(String uwSub, String loanId, String entryId) throws Exception {
        mvc.perform(post("/api/loans/{l}/coc/history/{e}/decision", loanId, entryId).with(underwriter(uwSub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isOk());
    }

    /**
     * CROWN JEWEL: an accepted CoC sets the revised-LE deadline to 3 GENERAL business days after
     * the decision date (which is "today" — the decision is recorded server-side at decision time).
     */
    @Test
    void acceptedCocDrivesRevisedLeDeadline() throws Exception {
        String lo = UUID.randomUUID().toString();
        String uw = UUID.randomUUID().toString();
        String loanId = createLoan(lo);
        String entryId = submitCoc(lo, loanId);
        acceptCoc(uw, loanId, entryId);

        // Decision recorded "now" (UTC). Deadline = decisionDate + 3 GENERAL business days.
        LocalDate expected =
                calc.addBusinessDays(LocalDate.now(java.time.ZoneOffset.UTC), 3,
                        BusinessDayType.GENERAL, GeneralBusinessDayConfig.DEFAULT);

        mvc.perform(get("/api/loans/{loanId}/disclosures/timing", loanId).with(lo(lo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revisedLeDeadline").value(expected.toString()));
    }

    /** No accepted CoC → no revised-LE clock; the field is null. */
    @Test
    void noAcceptedCocYieldsNullDeadline() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        mvc.perform(get("/api/loans/{loanId}/disclosures/timing", loanId).with(lo(lo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revisedLeDeadline").value(org.hamcrest.Matchers.nullValue()));
    }
}
