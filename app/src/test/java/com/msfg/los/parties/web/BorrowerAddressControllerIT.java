package com.msfg.los.parties.web;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BorrowerAddressControllerIT extends AbstractIntegrationTest {

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
        var res = mvc.perform(post("/api/loans/{id}/borrowers", loanId).with(lo()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Abbas\",\"lastName\":\"Hussein\",\"primary\":true}"))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    @Test
    void addPresentAddressThenList() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/addresses", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressType\":\"PRESENT\",\"city\":\"Centennial\",\"state\":\"CO\",\"ownershipType\":\"RENT\",\"residencyDurationYears\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.addressType").value("PRESENT"))
                .andExpect(jsonPath("$.data.city").value("Centennial"))
                .andExpect(jsonPath("$.data.state").value("CO"))
                .andExpect(jsonPath("$.data.ordinal").value(0));
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/addresses", loanId, borrowerId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void otherCompanyCannotAddAddress404() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId);
        var userB = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
        mvc.perform(post("/api/loans/{l}/borrowers/{b}/addresses", loanId, borrowerId).with(userB)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"addressType\":\"PRESENT\"}"))
                .andExpect(status().isNotFound());   // loan filtered out by tenant scope
    }

    @Test
    void noToken401() throws Exception {
        mvc.perform(get("/api/loans/{l}/borrowers/{b}/addresses", UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
