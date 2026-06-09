package com.msfg.los.reo.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReoControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addReo(String loanId, String marketValue) throws Exception {
        var body = "{\"propertyType\":\"SINGLE_FAMILY\",\"propertyStatus\":\"RETAINED\",\"marketValue\":%s}"
                .formatted(marketValue);
        var res = mvc.perform(post("/api/loans/{l}/reo", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- add REO → 201 + list 1 ---

    @Test
    void addReoReturns201AndListShowsOne() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/reo", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyType\":\"SINGLE_FAMILY\",\"propertyStatus\":\"RETAINED\",\"marketValue\":350000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.propertyType").value("SINGLE_FAMILY"))
                .andExpect(jsonPath("$.data.marketValue").value(350000))
                .andExpect(jsonPath("$.data.ordinal").value(0));

        mvc.perform(get("/api/loans/{l}/reo", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // --- negative marketValue → 400 ---

    @Test
    void negativeMarketValueReturns400() throws Exception {
        String loanId = createLoan();

        mvc.perform(post("/api/loans/{l}/reo", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyType\":\"SINGLE_FAMILY\",\"marketValue\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("marketValue")));
    }

    // --- 2nd REO gets ordinal 1 ---

    @Test
    void secondReoGetsOrdinalOne() throws Exception {
        String loanId = createLoan();

        addReo(loanId, "200000");

        mvc.perform(post("/api/loans/{l}/reo", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyType\":\"CONDOMINIUM\",\"propertyStatus\":\"RENTAL\",\"marketValue\":150000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ordinal").value(1));

        mvc.perform(get("/api/loans/{l}/reo", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].ordinal").value(1));
    }

    // --- PATCH updates a field, leaves others unchanged ---

    @Test
    void patchUpdatesMarketValueLeavesOthersUnchanged() throws Exception {
        String loanId = createLoan();

        var res = mvc.perform(post("/api/loans/{l}/reo", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyType\":\"TOWNHOUSE\",\"propertyStatus\":\"RETAINED\",\"marketValue\":400000,\"city\":\"Austin\"}"))
                .andExpect(status().isCreated()).andReturn();
        String reoId = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");

        mvc.perform(patch("/api/loans/{l}/reo/{r}", loanId, reoId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketValue\":420000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marketValue").value(420000))
                .andExpect(jsonPath("$.data.propertyType").value("TOWNHOUSE"))
                .andExpect(jsonPath("$.data.city").value("Austin"));
    }

    // --- DELETE → 204 + list empty ---

    @Test
    void deleteReoReturns204AndListEmpty() throws Exception {
        String loanId = createLoan();
        String reoId = addReo(loanId, "300000");

        mvc.perform(delete("/api/loans/{l}/reo/{r}", loanId, reoId).with(lo()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/loans/{l}/reo", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- cross-org JWT → 404 ---

    @Test
    void otherOrgCannotAddReoReturns404() throws Exception {
        String loanId = createLoan();

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(post("/api/loans/{l}/reo", loanId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyType\":\"SINGLE_FAMILY\",\"marketValue\":100000}"))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/reo", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
