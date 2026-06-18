package com.msfg.los.documents;

import com.jayway.jsonpath.JsonPath;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Folder tree end-to-end ITs. Uses the pre-existing MSFG org (DEFAULT_ORG, 00aa) because the
 * V18 per-org seed materialized the 17 folder_templates only for orgs that existed at migration
 * time — so a fresh loan under 00aa auto-seeds exactly 17 system folders + 1 root.
 */
class FolderControllerIT extends AbstractIntegrationTest {

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
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String childId(String body, String name) {
        // id of the folder whose displayName == name (filter yields a list → take element 0)
        java.util.List<String> ids = JsonPath.read(body, "$.data.folders[?(@.displayName == '" + name + "')].id");
        return ids.get(0);
    }

    private String rootIdOf(String body) {
        return JsonPath.read(body, "$.data.rootId");
    }

    // ── GET tree auto-seeds exactly 17 folders + 1 root, with the flagged specials ──────

    @Test
    void getTreeAutoSeedsRootPlusSeventeenSystemFolders() throws Exception {
        String loanId = createLoan();

        mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(18))            // 1 root + 17 templates
                .andExpect(jsonPath("$.data.rootId").isNotEmpty())
                .andExpect(jsonPath("$.data.folders", hasSize(18)))
                // root: parentId null, isSystem, name "Loan ..." (no borrower → loanNumber fallback)
                .andExpect(jsonPath("$.data.folders[?(@.parentId == null)]", hasSize(1)))
                .andExpect(jsonPath("$.data.folders[?(@.parentId == null)].isSystem", everyItem(is(true))))
                .andExpect(jsonPath("$.data.folders[?(@.parentId == null)].displayName", hasItem(startsWith("Loan "))))
                // the two specials by flag
                .andExpect(jsonPath("$.data.folders[?(@.isDeleteFolder == true)]", hasSize(1)))
                .andExpect(jsonPath("$.data.folders[?(@.isDeleteFolder == true)].displayName", contains("Delete")))
                .andExpect(jsonPath("$.data.folders[?(@.isOldLoanArchive == true)]", hasSize(1)))
                .andExpect(jsonPath("$.data.folders[?(@.isOldLoanArchive == true)].displayName", contains("Old Loan Files")))
                // a representative template by name
                .andExpect(jsonPath("$.data.folders[?(@.displayName == 'Submission')]", hasSize(1)))
                .andExpect(jsonPath("$.data.folders[?(@.displayName == 'Income')].isSystem", contains(true)));
    }

    // ── idempotent re-seed: a second GET does not duplicate folders ─────────────────────

    @Test
    void secondGetDoesNotDuplicateFolders() throws Exception {
        String loanId = createLoan();
        mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo()))
                .andExpect(jsonPath("$.data.count").value(18));
        mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(18));
    }

    // ── seed-defaults returns rootId + rootName, idempotent ─────────────────────────────

    @Test
    void seedDefaultsReturnsRootAndIsIdempotent() throws Exception {
        String loanId = createLoan();
        mvc.perform(post("/api/loans/{l}/folders/seed-defaults", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rootId").isNotEmpty())
                .andExpect(jsonPath("$.data.rootName").value(startsWith("Loan ")));
        // calling again does not blow up nor duplicate
        mvc.perform(post("/api/loans/{l}/folders/seed-defaults", loanId).with(lo()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo()))
                .andExpect(jsonPath("$.data.count").value(18));
    }

    // ── create a user folder under root → 201, isSystem false ───────────────────────────

    @Test
    void createUserFolderUnderRootReturns201() throws Exception {
        String loanId = createLoan();
        var tree = mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo())).andReturn();
        String rootId = JsonPath.read(tree.getResponse().getContentAsString(), "$.data.rootId");

        mvc.perform(post("/api/loans/{l}/folders", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"%s\",\"displayName\":\"My Stuff\"}".formatted(rootId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.displayName").value("My Stuff"))
                .andExpect(jsonPath("$.data.isSystem").value(false))
                .andExpect(jsonPath("$.data.parentId").value(rootId));
    }

    // ── duplicate sibling name (case-insensitive) → 400 ─────────────────────────────────

    @Test
    void duplicateSiblingNameReturns400() throws Exception {
        String loanId = createLoan();
        var tree = mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo())).andReturn();
        String rootId = JsonPath.read(tree.getResponse().getContentAsString(), "$.data.rootId");

        mvc.perform(post("/api/loans/{l}/folders", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"%s\",\"displayName\":\"Notes\"}".formatted(rootId)))
                .andExpect(status().isCreated());

        // case-insensitive collision against the existing "Notes"
        mvc.perform(post("/api/loans/{l}/folders", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"%s\",\"displayName\":\"  notes \"}".formatted(rootId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    // ── create under a foreign-loan parent → 400 (parent not in this loan) ──────────────

    @Test
    void createUnderForeignLoanParentReturns400() throws Exception {
        String loanA = createLoan();
        String loanB = createLoan();
        var treeB = mvc.perform(get("/api/loans/{l}/folders", loanB).with(lo())).andReturn();
        String rootB = JsonPath.read(treeB.getResponse().getContentAsString(), "$.data.rootId");

        // try to attach a folder to loanA using loanB's root as parent
        mvc.perform(post("/api/loans/{l}/folders", loanA).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"%s\",\"displayName\":\"Cross\"}".formatted(rootB)))
                .andExpect(status().isNotFound());
    }

    // ── rename a user folder OK + rename a system folder OK ─────────────────────────────

    @Test
    void renameUserAndSystemFoldersBothSucceed() throws Exception {
        String loanId = createLoan();
        var tree = mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo())).andReturn();
        String body = tree.getResponse().getContentAsString();
        String rootId = JsonPath.read(body, "$.data.rootId");
        String incomeId = childId(body, "Income"); // a system folder

        var created = mvc.perform(post("/api/loans/{l}/folders", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"%s\",\"displayName\":\"Temp\"}".formatted(rootId)))
                .andExpect(status().isCreated())
                .andReturn();
        String userId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        // user folder rename
        mvc.perform(patch("/api/loans/{l}/folders/{f}", loanId, userId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Renamed"));

        // system folder rename is allowed
        mvc.perform(patch("/api/loans/{l}/folders/{f}", loanId, incomeId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Income (W2)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Income (W2)"))
                .andExpect(jsonPath("$.data.isSystem").value(true));
    }

    // ── rename into an existing sibling name → 400 ──────────────────────────────────────

    @Test
    void renameIntoExistingSiblingNameReturns400() throws Exception {
        String loanId = createLoan();
        var tree = mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo())).andReturn();
        String body = tree.getResponse().getContentAsString();
        String rootId = JsonPath.read(body, "$.data.rootId");
        String assetsId = childId(body, "Assets"); // existing system sibling under root

        var created = mvc.perform(post("/api/loans/{l}/folders", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"%s\",\"displayName\":\"Misc\"}".formatted(rootId)))
                .andExpect(status().isCreated())
                .andReturn();
        String miscId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        // rename "Misc" → "Assets" collides with the existing system sibling
        mvc.perform(patch("/api/loans/{l}/folders/{f}", loanId, miscId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Assets\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    // ── soft-delete user folder → 204 then absent; soft-delete system folder → 400 ──────

    @Test
    void softDeleteUserFolderThenAbsentFromTree() throws Exception {
        String loanId = createLoan();
        var tree = mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo())).andReturn();
        String rootId = JsonPath.read(tree.getResponse().getContentAsString(), "$.data.rootId");

        var created = mvc.perform(post("/api/loans/{l}/folders", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"%s\",\"displayName\":\"Disposable\"}".formatted(rootId)))
                .andExpect(status().isCreated())
                .andReturn();
        String userId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mvc.perform(delete("/api/loans/{l}/folders/{f}", loanId, userId).with(lo()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.folders[?(@.displayName == 'Disposable')]", hasSize(0)))
                .andExpect(jsonPath("$.data.count").value(18)); // back to root + 17
    }

    @Test
    void softDeleteSystemFolderReturns400() throws Exception {
        String loanId = createLoan();
        var tree = mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo())).andReturn();
        java.util.List<String> deleteIds = JsonPath.read(tree.getResponse().getContentAsString(),
                "$.data.folders[?(@.isDeleteFolder == true)].id");
        String deleteFolderId = deleteIds.get(0);

        mvc.perform(delete("/api/loans/{l}/folders/{f}", loanId, deleteFolderId).with(lo()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Default folders cannot be deleted")));
    }

    // ── tenant isolation: a folder created under org A is invisible to org B ─────────────

    @Test
    void folderCreatedUnderOrgAInvisibleToOrgB() throws Exception {
        // Org A = DEFAULT_ORG (00aa, has templates). Create a loan + a distinctive user folder.
        String loanId = createLoan();
        var tree = mvc.perform(get("/api/loans/{l}/folders", loanId).with(lo())).andReturn();
        String rootId = JsonPath.read(tree.getResponse().getContentAsString(), "$.data.rootId");
        mvc.perform(post("/api/loans/{l}/folders", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"%s\",\"displayName\":\"SecretOrgA\"}".formatted(rootId)))
                .andExpect(status().isCreated());

        // Org B (a different org_id) cannot even resolve the loan → 404 (existence not leaked).
        String orgB = UUID.randomUUID().toString();
        var loB = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("org_id", orgB))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
        mvc.perform(get("/api/loans/{l}/folders", loanId).with(loB))
                .andExpect(status().isNotFound());
    }
}
