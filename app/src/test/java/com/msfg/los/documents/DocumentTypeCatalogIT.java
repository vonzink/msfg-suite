package com.msfg.los.documents;

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

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Document-type catalog ITs (staff read + admin CRUD).
 *
 * <p>Runs against a dedicated org (CAT_ORG) seeded in {@code @BeforeEach} by copying the V18 seed
 * rows from the pre-existing MSFG org — so admin writes here never pollute DEFAULT_ORG's exact
 * seed counts (which {@code DocumentsPhase1RlsIT} asserts). Tenant isolation is exercised against a
 * second fresh org.
 *
 * <p>Per-tenant catalog admin routes live under {@code /api/admin/document-types/**}; in the
 * {@code test} profile the real SecurityConfig URL rule gates that prefix to
 * {@code hasAnyRole("ADMIN","PLATFORM_ADMIN")} (cutover Phase 2/3 T1 — a tenant ADMIN now manages
 * its own catalogs). Admin CRUD tests authenticate as a tenant {@code ROLE_ADMIN}; a
 * {@code ROLE_PLATFORM_ADMIN}-only caller is also accepted, and a non-admin (ROLE_LO) is 403.
 */
class DocumentTypeCatalogIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    static final String USER = UUID.randomUUID().toString();
    /** Writable org for CRUD tests (mutated → never used for exact-count reads). */
    static final String CAT_ORG = "00000000-0000-0000-0000-0000000000c1";
    /** Read-only org for exact-count seed assertions (no test ever writes to it). */
    static final String CAT_ORG_RO = "00000000-0000-0000-0000-0000000000c2";

    @BeforeEach
    void seedDedicatedOrgs() {
        seedOrgWithTypes(CAT_ORG, "cat-dt");
        seedOrgWithTypes(CAT_ORG_RO, "cat-dt-ro");
    }

    /** Insert the org + copy the V18 document_type seed from DEFAULT_ORG once (DB owner → RLS bypassed). */
    private void seedOrgWithTypes(String org, String slug) {
        jdbc.update("insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,?,?,'ACTIVE','{}'::jsonb) on conflict (id) do nothing", org, slug, slug);
        Integer existing = jdbc.queryForObject(
                "select count(*) from document_type where org_id = ?::uuid", Integer.class, org);
        if (existing == null || existing == 0) {
            jdbc.update(
                "insert into document_type (id,org_id,version,name,slug,default_folder_name," +
                "required_for_milestones,allowed_mime_types,max_file_size_bytes,is_active,sort_order) " +
                "select gen_random_uuid(), ?::uuid, 0, name, slug, default_folder_name, " +
                "required_for_milestones, allowed_mime_types, max_file_size_bytes, is_active, sort_order " +
                "from document_type where org_id = ?::uuid", org, DEFAULT_ORG);
        }
    }

    private RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject(USER).claim("org_id", CAT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    /** Staff caller in the read-only org. */
    private RequestPostProcessor staffRo() {
        return jwt().jwt(j -> j.subject(USER).claim("org_id", CAT_ORG_RO))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    /** Tenant ADMIN — manages its own org's catalog (the new Phase 2/3 T1 gating). */
    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(USER).claim("org_id", CAT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** Platform admin WITHOUT the tenant ADMIN role — still accepted via hasAnyRole. */
    private RequestPostProcessor platformAdminOnly() {
        return jwt().jwt(j -> j.subject(USER).claim("org_id", CAT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    private RequestPostProcessor adminOrg(String orgId) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", orgId))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    // ── staff reads ────────────────────────────────────────────────────────────────────

    @Test
    void listReturnsTheSixteenSeededTypesOrderedWithOtherLast() throws Exception {
        // read-only org → exactly the 16 seeded types regardless of test order
        mvc.perform(get("/api/document-types").with(staffRo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(16))
                .andExpect(jsonPath("$.data.documentTypes", hasSize(16)))
                // first entry is sort_order 1 (W-2); last is 'other' at 99
                .andExpect(jsonPath("$.data.documentTypes[0].slug").value("w-2"))
                .andExpect(jsonPath("$.data.documentTypes[15].slug").value("other"))
                .andExpect(jsonPath("$.data.documentTypes[15].sortOrder").value(99))
                // staff-only surface: no borrower-visibility field is exposed
                .andExpect(jsonPath("$.data.documentTypes[0].borrowerVisible").doesNotExist());
    }

    @Test
    void getBySlugReturnsTheType() throws Exception {
        mvc.perform(get("/api/document-types/{slug}", "bank-statement").with(staffRo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("bank-statement"))
                .andExpect(jsonPath("$.data.name").value("Bank Statement"))
                .andExpect(jsonPath("$.data.defaultFolderName").value("04 Assets"));
    }

    @Test
    void getByUnknownSlugReturns404() throws Exception {
        mvc.perform(get("/api/document-types/{slug}", "no-such-slug").with(staffRo()))
                .andExpect(status().isNotFound());
    }

    // ── admin auth gate ──────────────────────────────────────────────────────────────────

    @Test
    void nonAdminCallerCannotReachAdminRoute() throws Exception {
        mvc.perform(get("/api/admin/document-types").with(staff()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminListReturnsAllTypes() throws Exception {
        mvc.perform(get("/api/admin/document-types").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(16))));
    }

    @Test
    void platformAdminWithoutTenantAdminRoleIsAlsoAllowed() throws Exception {
        mvc.perform(get("/api/admin/document-types").with(platformAdminOnly()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(16))));
    }

    // ── admin create / dup-slug / update collision / soft-delete ─────────────────────────

    @Test
    void adminCreateThenDuplicateSlugReturns400() throws Exception {
        String slug = "custom-" + UUID.randomUUID();
        String body = """
                {"name":"Custom Doc","slug":"%s","defaultFolderName":"02 Borrower Documents",
                 "allowedMimeTypes":"application/pdf","maxFileSizeBytes":1048576,"sortOrder":50}
                """.formatted(slug);

        mvc.perform(post("/api/admin/document-types").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").value(slug))
                .andExpect(jsonPath("$.data.isActive").value(true));

        // duplicate slug → 400
        mvc.perform(post("/api/admin/document-types").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    @Test
    void adminUpdateIntoExistingSlugReturns400() throws Exception {
        String slugA = "type-a-" + UUID.randomUUID();
        String idA = createType(slugA);
        String slugB = "type-b-" + UUID.randomUUID();
        createType(slugB);

        // rename A's slug → B collides
        String body = """
                {"name":"Type A","slug":"%s","sortOrder":50}
                """.formatted(slugB);
        mvc.perform(put("/api/admin/document-types/{id}", idA).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    @Test
    void adminDeleteDeactivatesThenAbsentFromActiveList() throws Exception {
        String slug = "ephemeral-" + UUID.randomUUID();
        String id = createType(slug);

        mvc.perform(delete("/api/admin/document-types/{id}", id).with(admin()))
                .andExpect(status().isNoContent());

        // gone from the staff active list
        mvc.perform(get("/api/document-types").with(staff()))
                .andExpect(jsonPath("$.data.documentTypes[?(@.slug == '" + slug + "')]", hasSize(0)));
        // still present (inactive) in the admin all-list
        mvc.perform(get("/api/admin/document-types/{id}", id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));
    }

    // ── tenant isolation: org A's catalog edits invisible to org B ───────────────────────

    @Test
    void typeCreatedUnderOrgAInvisibleToOrgB() throws Exception {
        String slug = "secret-a-" + UUID.randomUUID();
        // org A = DEFAULT_ORG
        createType(slug);

        // org B (fresh org_id) — admin in B sees none of A's rows (no seed either → empty list)
        String orgB = UUID.randomUUID().toString();
        var res = mvc.perform(get("/api/admin/document-types").with(adminOrg(orgB)))
                .andExpect(status().isOk())
                .andReturn();
        List<String> slugs = JsonPath.read(res.getResponse().getContentAsString(), "$.data[*].slug");
        org.assertj.core.api.Assertions.assertThat(slugs).doesNotContain(slug);
    }

    private String createType(String slug) throws Exception {
        String body = """
                {"name":"%s","slug":"%s","sortOrder":50}
                """.formatted(slug, slug);
        var res = mvc.perform(post("/api/admin/document-types").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }
}
