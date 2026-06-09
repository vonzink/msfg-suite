package com.msfg.los.financials.web;

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

class AssetControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

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

    private String addAsset(String loanId, String borrowerId, String assetType, String value) throws Exception {
        String body = value != null
                ? "{\"assetType\":\"%s\",\"cashOrMarketValue\":%s}".formatted(assetType, value)
                : "{\"assetType\":\"%s\"}".formatted(assetType);
        var res = mvc.perform(post("/api/loans/{l}/borrowers/{b}/assets", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- add then list ---

    @Test
    void addAssetThenListLengthOne() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/assets", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetType\":\"CHECKING\",\"cashOrMarketValue\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assetType").value("CHECKING"))
                .andExpect(jsonPath("$.data.cashOrMarketValue").value(10000))
                .andExpect(jsonPath("$.data.ordinal").value(0));

        mvc.perform(get("/api/loans/{l}/borrowers/{b}/assets", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // --- negative cashOrMarketValue → 400 ---

    @Test
    void negativeCashOrMarketValueReturns400() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/assets", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetType\":\"SAVINGS\",\"cashOrMarketValue\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("cashOrMarketValue")));
    }

    // --- ordinal increments ---

    @Test
    void secondAssetGetsOrdinalOne() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        addAsset(loanId, borrowerId, "CHECKING", "5000");

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/assets", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetType\":\"SAVINGS\",\"cashOrMarketValue\":2000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ordinal").value(1));

        mvc.perform(get("/api/loans/{l}/borrowers/{b}/assets", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].ordinal").value(1));
    }

    // --- PATCH partial update ---

    @Test
    void patchUpdatesValueLeavesOthersUnchanged() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        var res = mvc.perform(post("/api/loans/{l}/borrowers/{b}/assets", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetType\":\"CHECKING\",\"financialInstitution\":\"BankOfTest\",\"cashOrMarketValue\":5000}"))
                .andExpect(status().isCreated()).andReturn();
        String assetId = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");

        mvc.perform(patch("/api/loans/{l}/borrowers/{b}/assets/{a}", loanId, borrowerId, assetId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cashOrMarketValue\":9999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cashOrMarketValue").value(9999))
                .andExpect(jsonPath("$.data.assetType").value("CHECKING"))
                .andExpect(jsonPath("$.data.financialInstitution").value("BankOfTest"));
    }

    // --- DELETE then list empty ---

    @Test
    void deleteAssetThenListEmpty() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        String assetId = addAsset(loanId, borrowerId, "BONDS", "15000");

        mvc.perform(delete("/api/loans/{l}/borrowers/{b}/assets/{a}", loanId, borrowerId, assetId).with(lo()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/loans/{l}/borrowers/{b}/assets", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // --- cross-org 404 ---

    @Test
    void otherOrgCannotAddAssetReturns404() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(post("/api/loans/{l}/borrowers/{b}/assets", loanId, borrowerId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetType\":\"SAVINGS\",\"cashOrMarketValue\":5000}"))
                .andExpect(status().isNotFound());
    }

    // --- no token 401 ---

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/assets", UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
