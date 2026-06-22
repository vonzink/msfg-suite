package com.msfg.los.origination;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.platform.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Crown-jewel IT for the borrower funnel hand-off {@code POST /api/loans/intake} (Phase A4).
 *
 * <p>Runs under the {@code local} profile so {@code LocalDevSecurityConfig.DevPrincipalFilter} is
 * wired and we can act as a real BORROWER via the dev-header identity bridge ({@code X-Dev-Sub} /
 * {@code X-Dev-Roles} / {@code X-Dev-Org}) — exactly how mortgage-app forwards a signed-in borrower
 * locally. The {@code test} profile is also active for the Testcontainers-friendly config
 * (Flyway + ddl validate + npi dev key + no-handler-found mapping); the {@code local} profile wins
 * security wiring because {@link com.msfg.los.config.SecurityConfig} is {@code @Profile("!local")}.
 *
 * <p>Asserts: (1) intake creates a loan + primary borrower linked to the caller's sub → 200;
 * (2) re-POSTing the same {@code sourceLeadId} is idempotent (same loanId, no duplicate); and
 * (3) the borrower can then read the loan back via {@code GET /api/me/loans} (role-scoped borrower
 * linkage), proving the link landed.
 */
@SpringBootTest
@ActiveProfiles({"test", "local"})
@AutoConfigureMockMvc
@Import(IntakeControllerIT.TestBeans.class)
class IntakeControllerIT {

    static final String BORROWER_SUB = "00000000-0000-0000-0000-0000000000b0";
    static final String DEV_ORG = "00000000-0000-0000-0000-0000000000aa";

    // Singleton container — started once per JVM (mirrors AbstractIntegrationTest's pattern).
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        reg.add("spring.datasource.username", POSTGRES::getUsername);
        reg.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mvc;

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    /** Act as the dev BORROWER via the local-profile dev-header identity bridge. */
    private MockHttpServletRequestBuilder asBorrower(MockHttpServletRequestBuilder b) {
        return b.header("X-Dev-Sub", BORROWER_SUB)
                .header("X-Dev-Roles", "Borrower")
                .header("X-Dev-Org", DEV_ORG);
    }

    private static final String INTAKE_BODY = """
            {"sourceLeadId":"lead-A4","loanPurpose":"PURCHASE",
             "borrower":{"firstName":"Ann","lastName":"Buyer","email":"borrower@dev.local","phone":"555-0100"},
             "property":{"addressLine1":"1 Main St","city":"Denver","state":"CO","postalCode":"80202","estimatedValue":350000}}
            """;

    @Test
    void intakeCreatesLoanLinksBorrowerIsIdempotentAndVisibleViaMeLoans() throws Exception {
        // 1) First intake → 200, capture the loan id.
        var first = mvc.perform(asBorrower(post("/api/loans/intake"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INTAKE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loanId").value(notNullValue()))
                .andExpect(jsonPath("$.data.loanNumber").value(notNullValue()))
                .andReturn();
        String loanId = JsonPath.read(first.getResponse().getContentAsString(), "$.data.loanId");

        // 2) Same sourceLeadId again → 200, SAME loanId (idempotent — no duplicate loan).
        mvc.perform(asBorrower(post("/api/loans/intake"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INTAKE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loanId").value(loanId));

        // 3) The borrower sees the loan via /me/loans (role-scoped to their linkage).
        //    Envelope: ApiResponse<PagedResponse<LoanListItemResponse>> → $.data.items[].id.
        mvc.perform(asBorrower(get("/api/me/loans"))
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id").value(hasItem(loanId)));
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> { throw new UnsupportedOperationException("JwtDecoder not used in tests"); };
        }
    }
}
