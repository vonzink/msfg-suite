package com.msfg.los.disclosures.web;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.disclosures.service.DeliveryRequest;
import com.msfg.los.disclosures.service.DeliveryResult;
import com.msfg.los.disclosures.service.DeliveryStatus;
import com.msfg.los.disclosures.service.DisclosureGenerationRequest;
import com.msfg.los.disclosures.service.DisclosureGenerationResult;
import com.msfg.los.disclosures.service.DisclosureVendorPort;
import com.msfg.los.disclosures.service.UcdExportResult;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Error-seam IT for disclosure issuance: when the vendor port's {@code generate()} throws, the
 * outer issuance transaction rolls back, but the ERROR audit row must SURVIVE because it is written
 * by {@link com.msfg.los.disclosures.service.DisclosureIssuanceErrorRecorder} in a REQUIRES_NEW
 * transaction. Without that recorder, a same-transaction save of the ERROR row would vanish with the
 * rollback (the dead-code RED: 0 ERROR rows). A @Primary stub vendor that throws stands in for a
 * vendor outage.
 */
@Import(DisclosureErrorSeamIT.FailingVendorConfig.class)
class DisclosureErrorSeamIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @TestConfiguration
    static class FailingVendorConfig {
        @Bean
        @Primary
        DisclosureVendorPort failingVendor() {
            return new DisclosureVendorPort() {
                @Override
                public DisclosureGenerationResult generate(DisclosureGenerationRequest request) {
                    throw new RuntimeException("vendor down (test)");
                }

                @Override
                public DeliveryResult send(DeliveryRequest request) {
                    throw new UnsupportedOperationException("unreachable — generate throws first");
                }

                @Override
                public DeliveryStatus getStatus(String vendorReference) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public UcdExportResult exportUcd(UUID loanId, UUID disclosureId) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    private RequestPostProcessor as(String sub, String role) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private String createLoan(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    @Test
    void vendorGenerateFailure_recordsErrorRow_survivesRollback() throws Exception {
        String lo = UUID.randomUUID().toString();
        String loanId = createLoan(lo);

        // The vendor blows up → the request fails (5xx), but the ERROR row is committed out-of-band.
        mvc.perform(post("/api/loans/{loanId}/disclosures/loan-estimate", loanId)
                        .with(as(lo, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is5xxServerError());

        Integer errorRows = jdbc.queryForObject(
                "select count(*) from disclosure_issuance "
                        + "where loan_id = ?::uuid and status = 'ERROR' and error_message like '%vendor down%'",
                Integer.class, loanId);
        assertThat(errorRows).isEqualTo(1);
    }
}
