package com.msfg.los.identity;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Functional ITs for GET /api/me + /api/me/loans (cutover Phase 2/3 T2):
 * materialize-once idempotency, JWT-claim projection, role refresh, role-scoped loan listing,
 * and tenant isolation across two orgs.
 */
class MeIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static final String ORG_B = "00000000-0000-0000-0000-0000000000bb";

    @BeforeEach
    void seedOrgB() {
        jdbc.update(
            "insert into organization (id,version,name,slug,status,settings) " +
            "values (?::uuid,0,'org-me-b','org-me-b','ACTIVE','{}'::jsonb) on conflict (id) do nothing",
            ORG_B);
    }

    private RequestPostProcessor as(String sub, String role, String org, String name, String email) {
        return jwt().jwt(j -> {
            j.subject(sub).claim("org_id", org);
            if (name != null) j.claim("name", name);
            if (email != null) j.claim("email", email);
        }).authorities(new SimpleGrantedAuthority(role));
    }

    // Email is unique per sub: the (org_id, email) unique key + the resolveOrCreate email fallback
    // mean a shared "jane@msfg.test" would make one test's row hijack another's.
    private RequestPostProcessor as(String sub, String role) {
        return as(sub, role, DEFAULT_ORG, "Jane Doe", sub.substring(0, 8) + "@msfg.test");
    }

    // The test JdbcTemplate connects as the Testcontainers superuser, which bypasses RLS — so it
    // sees every row regardless of GUC. We therefore assert tenant scoping by querying the actual
    // org_id column rather than relying on the policy at the test layer (RLS itself is proven in
    // UserAccountRlsIT, which drops to app_user). Count rows for a (sub, org) pair.
    private int rowCount(String sub, String org) {
        Integer n = jdbc.queryForObject(
            "select count(*) from user_account where id = ?::uuid and org_id = ?::uuid",
            Integer.class, sub, org);
        return n == null ? 0 : n;
    }

    private String roleFor(String sub, String org) {
        var rows = jdbc.queryForList(
            "select role from user_account where id = ?::uuid and org_id = ?::uuid",
            String.class, sub, org);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── materialize + idempotency ──────────────────────────────────────────────

    @Test
    void me_materializesRowOnceAndIsIdempotent() throws Exception {
        String sub = UUID.randomUUID().toString();

        mvc.perform(get("/api/me").with(as(sub, "ROLE_PROCESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(sub))
                .andExpect(jsonPath("$.data.email").value(sub.substring(0, 8) + "@msfg.test"))
                .andExpect(jsonPath("$.data.name").value("Jane Doe"))
                .andExpect(jsonPath("$.data.initials").value("JD"))
                .andExpect(jsonPath("$.data.role").value("PROCESSOR"))
                .andExpect(jsonPath("$.data.orgId").value(DEFAULT_ORG))
                .andExpect(jsonPath("$.data.roles[0]").value("ROLE_PROCESSOR"));

        assertThat(rowCount(sub, DEFAULT_ORG)).isEqualTo(1);

        // second call does NOT duplicate
        mvc.perform(get("/api/me").with(as(sub, "ROLE_PROCESSOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(sub));

        assertThat(rowCount(sub, DEFAULT_ORG)).isEqualTo(1);
    }

    @Test
    void me_refreshesPersistedRoleWhenJwtRoleChanges() throws Exception {
        String sub = UUID.randomUUID().toString();

        mvc.perform(get("/api/me").with(as(sub, "ROLE_LO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("LO"));

        // same subject, now an ADMIN → row updates in place (no dup)
        mvc.perform(get("/api/me").with(as(sub, "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        assertThat(rowCount(sub, DEFAULT_ORG)).isEqualTo(1);
        assertThat(roleFor(sub, DEFAULT_ORG)).isEqualTo("ADMIN");
    }

    @Test
    void me_synthesizesEmailWhenJwtHasNoEmailClaim() throws Exception {
        String sub = UUID.randomUUID().toString();
        mvc.perform(get("/api/me")
                        .with(as(sub, "ROLE_LO", DEFAULT_ORG, "No Email", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(sub + "@unknown.local"));
    }

    // ── /me/loans role scoping ─────────────────────────────────────────────────

    private String createLoanOwnedBy(String loSub) throws Exception {
        var res = mvc.perform(post("/api/loans").with(as(loSub, "ROLE_LO"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(loSub)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private boolean myLoansContains(RequestPostProcessor who, String loanId) throws Exception {
        int page = 0;
        while (true) {
            String body = mvc.perform(get("/api/me/loans?size=100&page=" + page).with(who))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var doc = JsonPath.parse(body);
            List<String> ids = doc.read("$.data.items[*].id");
            if (ids.contains(loanId)) return true;
            int totalPages = doc.read("$.data.totalPages", Integer.class);
            if (++page >= totalPages) return false;
        }
    }

    @Test
    void meLoans_orgWideRoleSeesAnotherLosLoan() throws Exception {
        String id = createLoanOwnedBy(UUID.randomUUID().toString());
        // ADMIN, MANAGER, PROCESSOR are all org-wide
        for (String role : List.of("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_PROCESSOR")) {
            assertThat(myLoansContains(as(UUID.randomUUID().toString(), role), id))
                    .as("/me/loans for %s lists another LO's loan", role).isTrue();
        }
    }

    @Test
    void meLoans_loSeesOnlyOwnLoans() throws Exception {
        String otherSub = UUID.randomUUID().toString();
        String otherLoan = createLoanOwnedBy(otherSub);

        String mySub = UUID.randomUUID().toString();
        String myLoan = createLoanOwnedBy(mySub);

        RequestPostProcessor me = as(mySub, "ROLE_LO");
        assertThat(myLoansContains(me, myLoan)).as("LO sees own loan").isTrue();
        assertThat(myLoansContains(me, otherLoan)).as("LO does not see another LO's loan").isFalse();
    }

    // ── tenant isolation ───────────────────────────────────────────────────────

    @Test
    void me_rowIsInvisibleToOtherOrg() throws Exception {
        // A sub belongs to exactly one org (one-company-per-user). Materialize one user per org.
        String subA = UUID.randomUUID().toString();
        String subB = UUID.randomUUID().toString();

        mvc.perform(get("/api/me").with(as(subA, "ROLE_PROCESSOR", DEFAULT_ORG, "Org A User", "a@a.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgId").value(DEFAULT_ORG));
        mvc.perform(get("/api/me").with(as(subB, "ROLE_PROCESSOR", ORG_B, "Org B User", "b@b.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgId").value(ORG_B));

        // Each row is visible only within its own org (RLS + @TenantId scoping).
        assertThat(rowCount(subA, DEFAULT_ORG)).as("A's row in A's org").isEqualTo(1);
        assertThat(rowCount(subA, ORG_B)).as("A's row is invisible to org B").isZero();
        assertThat(rowCount(subB, ORG_B)).as("B's row in B's org").isEqualTo(1);
        assertThat(rowCount(subB, DEFAULT_ORG)).as("B's row is invisible to org A").isZero();
    }

    @Test
    void meLoans_doesNotBleedAcrossOrgs() throws Exception {
        // loan lives in DEFAULT_ORG
        String id = createLoanOwnedBy(UUID.randomUUID().toString());
        // an org-wide role in ORG_B must not see DEFAULT_ORG's loan
        boolean visible = myLoansContains(
                as(UUID.randomUUID().toString(), "ROLE_PROCESSOR", ORG_B, "B Proc", "p@b.test"), id);
        assertThat(visible).as("ORG_B /me/loans never lists DEFAULT_ORG's loan").isFalse();
    }
}
