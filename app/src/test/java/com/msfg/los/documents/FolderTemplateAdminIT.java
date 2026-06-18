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
 * Folder-template admin CRUD ITs.
 *
 * <p>Runs against a dedicated org (FT_ORG) seeded in {@code @BeforeEach} by copying the V18 seed
 * rows from the pre-existing MSFG org — so admin writes here never pollute DEFAULT_ORG's exact
 * seed counts (which {@code DocumentsPhase1RlsIT} asserts). The copy carries the one active Delete
 * template + one active Old-Loan-Archive template, so the singleton + undeletable-Delete rules are
 * exercised against real seeded state. Tenant isolation uses a second fresh org.
 *
 * <p>Per-tenant catalog admin routes ({@code /api/admin/folder-templates/**}) are gated to
 * {@code hasAnyRole("ADMIN","PLATFORM_ADMIN")} by the test-profile SecurityConfig URL rule
 * (cutover Phase 2/3 T1 — a tenant ADMIN manages its own catalogs). CRUD tests authenticate as
 * a tenant {@code ROLE_ADMIN}; a {@code ROLE_PLATFORM_ADMIN}-only caller is also accepted; a
 * non-admin (ROLE_LO) → 403.
 */
class FolderTemplateAdminIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    static final String USER = UUID.randomUUID().toString();
    static final String FT_ORG = "00000000-0000-0000-0000-0000000000c3";

    @BeforeEach
    void seedDedicatedOrg() {
        jdbc.update("insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,'cat-ft','cat-ft','ACTIVE','{}'::jsonb) on conflict (id) do nothing", FT_ORG);
        Integer existing = jdbc.queryForObject(
                "select count(*) from folder_template where org_id = ?::uuid", Integer.class, FT_ORG);
        if (existing == null || existing == 0) {
            jdbc.update(
                "insert into folder_template (id,org_id,version,display_name,sort_key," +
                "is_old_loan_archive,is_delete_folder,is_active,sort_order,eval_prompt) " +
                "select gen_random_uuid(), ?::uuid, 0, display_name, sort_key, " +
                "is_old_loan_archive, is_delete_folder, is_active, sort_order, eval_prompt " +
                "from folder_template where org_id = ?::uuid", FT_ORG, DEFAULT_ORG);
        }
    }

    private RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject(USER).claim("org_id", FT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    /** Tenant ADMIN — manages its own org's catalog (the new Phase 2/3 T1 gating). */
    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(USER).claim("org_id", FT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** Platform admin WITHOUT the tenant ADMIN role — still accepted via hasAnyRole. */
    private RequestPostProcessor platformAdminOnly() {
        return jwt().jwt(j -> j.subject(USER).claim("org_id", FT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    private RequestPostProcessor adminOrg(String orgId) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", orgId))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    // ── auth gate ────────────────────────────────────────────────────────────────────────

    @Test
    void nonAdminCallerCannotReachAdminRoute() throws Exception {
        mvc.perform(get("/api/admin/folder-templates").with(staff()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminListReturnsTheSeededTemplates() throws Exception {
        mvc.perform(get("/api/admin/folder-templates").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(17))))
                .andExpect(jsonPath("$.data[?(@.isDeleteFolder == true && @.isActive == true)]", hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.isOldLoanArchive == true && @.isActive == true)]", hasSize(1)));
    }

    @Test
    void platformAdminWithoutTenantAdminRoleIsAlsoAllowed() throws Exception {
        mvc.perform(get("/api/admin/folder-templates").with(platformAdminOnly()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(17))));
    }

    // ── create + dup display_name → 400 ──────────────────────────────────────────────────

    @Test
    void adminCreateThenDuplicateNameReturns400() throws Exception {
        String name = "Custom Folder " + UUID.randomUUID();
        String body = """
                {"displayName":"%s","sortKey":"50","sortOrder":50}
                """.formatted(name);

        mvc.perform(post("/api/admin/folder-templates").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.displayName").value(name))
                .andExpect(jsonPath("$.data.isActive").value(true));

        mvc.perform(post("/api/admin/folder-templates").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    // ── evalPrompt pass-through + null clears ────────────────────────────────────────────

    @Test
    void adminCreateThenUpdateEvalPromptThenClear() throws Exception {
        String name = "AI Folder " + UUID.randomUUID();
        String id = createTemplate("""
                {"displayName":"%s","sortOrder":51,"evalPrompt":"Score the docs here"}
                """.formatted(name));

        mvc.perform(get("/api/admin/folder-templates/{id}", id).with(admin()))
                .andExpect(jsonPath("$.data.evalPrompt").value("Score the docs here"));

        // PUT with null evalPrompt clears it
        mvc.perform(put("/api/admin/folder-templates/{id}", id).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"%s\",\"sortOrder\":51}".formatted(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evalPrompt").doesNotExist());
    }

    // ── singletons: a second active Delete / Old-Loan-Archive → 400 ──────────────────────

    @Test
    void secondActiveDeleteTemplateReturns400() throws Exception {
        // the seed already has one active Delete template, so a new active deleteFolder → 400
        String body = """
                {"displayName":"Second Delete %s","deleteFolder":true,"sortOrder":60}
                """.formatted(UUID.randomUUID());
        mvc.perform(post("/api/admin/folder-templates").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Delete folder template already exists")));
    }

    @Test
    void secondActiveOldLoanArchiveTemplateReturns400() throws Exception {
        String body = """
                {"displayName":"Second Archive %s","oldLoanArchive":true,"sortOrder":61}
                """.formatted(UUID.randomUUID());
        mvc.perform(post("/api/admin/folder-templates").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Old Loan Archive template already exists")));
    }

    // ── the Delete template cannot be deactivated → 400 ──────────────────────────────────

    @Test
    void deleteOnTheDeleteTemplateReturns400() throws Exception {
        String deleteTemplateId = activeSpecialId("isDeleteFolder");
        mvc.perform(delete("/api/admin/folder-templates/{id}", deleteTemplateId).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("Delete folder template is required and cannot be deactivated")));
    }

    // ── a plain custom template CAN be deactivated → 204, then inactive ──────────────────

    @Test
    void deleteOnAPlainTemplateDeactivates() throws Exception {
        String name = "Disposable " + UUID.randomUUID();
        String id = createTemplate("{\"displayName\":\"%s\",\"sortOrder\":62}".formatted(name));

        mvc.perform(delete("/api/admin/folder-templates/{id}", id).with(admin()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/folder-templates/{id}", id).with(admin()))
                .andExpect(jsonPath("$.data.isActive").value(false));
    }

    // ── tenant isolation: org A's templates invisible to org B ───────────────────────────

    @Test
    void templateCreatedUnderOrgAInvisibleToOrgB() throws Exception {
        String name = "SecretOrgA " + UUID.randomUUID();
        createTemplate("{\"displayName\":\"%s\",\"sortOrder\":63}".formatted(name));

        String orgB = UUID.randomUUID().toString();
        var res = mvc.perform(get("/api/admin/folder-templates").with(adminOrg(orgB)))
                .andExpect(status().isOk())
                .andReturn();
        List<String> names = JsonPath.read(res.getResponse().getContentAsString(), "$.data[*].displayName");
        org.assertj.core.api.Assertions.assertThat(names).doesNotContain(name);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private String createTemplate(String body) throws Exception {
        var res = mvc.perform(post("/api/admin/folder-templates").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    /** Id of the single active template flagged by the given boolean field (isDeleteFolder/isOldLoanArchive). */
    private String activeSpecialId(String flagField) throws Exception {
        var res = mvc.perform(get("/api/admin/folder-templates").with(admin())).andReturn();
        List<String> ids = JsonPath.read(res.getResponse().getContentAsString(),
                "$.data[?(@." + flagField + " == true && @.isActive == true)].id");
        return ids.get(0);
    }
}
