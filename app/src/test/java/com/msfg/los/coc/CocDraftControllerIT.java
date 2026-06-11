package com.msfg.los.coc;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CocDraftControllerIT extends AbstractIntegrationTest {

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
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- GET before save → 200, empty arrays ---

    @Test
    void getBeforeSaveReturnsEmpty() throws Exception {
        String loanId = createLoan();

        mvc.perform(get("/api/loans/{l}/coc/draft", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.structureChanges", hasSize(0)))
                .andExpect(jsonPath("$.data.feeChanges", hasSize(0)));
    }

    // --- PUT draft with StructureChange + FeeChange → 200 ---

    @Test
    void putDraftAndGetRoundTrips() throws Exception {
        String loanId = createLoan();

        String body = """
                {
                  "structureChanges": [{"field":"loanAmount","label":"Loan Amount","currentValue":"300000","requestedValue":"320000"}],
                  "feeChanges": [{"section":"A","label":"Origination Fee","currentValue":100,"requestedValue":150,"reason":"Fee increase","hasInvoice":"No"}]
                }
                """;

        mvc.perform(put("/api/loans/{l}/coc/draft", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feeChanges[0].requestedValue").value(150))
                .andExpect(jsonPath("$.data.structureChanges[0].field").value("loanAmount"));

        // GET verifies jsonb round-trip
        mvc.perform(get("/api/loans/{l}/coc/draft", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feeChanges[0].requestedValue").value(150))
                .andExpect(jsonPath("$.data.structureChanges[0].field").value("loanAmount"));
    }

    // --- 2nd PUT → still only 1 row (upsert) ---

    @Test
    void secondPutUpserts() throws Exception {
        String loanId = createLoan();

        String body1 = "{\"feeChanges\":[{\"section\":\"A\",\"label\":\"Fee\",\"currentValue\":100,\"requestedValue\":150,\"reason\":\"x\",\"hasInvoice\":\"No\"}]}";
        String body2 = "{\"feeChanges\":[{\"section\":\"B\",\"label\":\"Fee2\",\"currentValue\":200,\"requestedValue\":250,\"reason\":\"y\",\"hasInvoice\":\"Yes\"}]}";

        mvc.perform(put("/api/loans/{l}/coc/draft", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isOk());

        mvc.perform(put("/api/loans/{l}/coc/draft", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isOk());

        int count = jdbc.queryForObject(
                "select count(*) from coc_draft where loan_id = ?::uuid",
                Integer.class, loanId);
        assertEquals(1, count);
    }

    // --- cross-org → 404 ---

    @Test
    void crossOrgReturns404() throws Exception {
        String loanId = createLoan();

        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        mvc.perform(get("/api/loans/{l}/coc/draft", loanId).with(otherOrg))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noTokenReturns401() throws Exception {
        mvc.perform(get("/api/loans/{l}/coc/draft", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
