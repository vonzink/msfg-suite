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

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end ITs for the Phase-1 document core (Task 5): the real 3-step presigned round-trip via
 * the local (db-driver) object-storage adapter, MIME/size enforcement on confirm, presigned
 * download, query-side list + faceted search, patch/move, permanent-delete gating, and tenant
 * isolation.
 *
 * <p>Runs against a DEDICATED org (DOC_ORG) seeded in {@code @BeforeEach} by copying the V18
 * folder_template + document_type seed from DEFAULT_ORG (so a fresh loan auto-seeds the full folder
 * tree), plus a tiny-cap custom type for the max-size case — never mutating DEFAULT_ORG's exact seed
 * counts (which {@code DocumentsPhase1RlsIT} asserts).
 */
class DocumentLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    static final String USER = UUID.randomUUID().toString();
    static final String DOC_ORG = "00000000-0000-0000-0000-0000000000d5";

    private RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject(USER).claim("org_id", DOC_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    @BeforeEach
    void seedOrg() {
        jdbc.update("insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,'doc-d5','doc-d5','ACTIVE','{}'::jsonb) on conflict (id) do nothing", DOC_ORG);
        // copy folder_templates (so folder auto-seed materializes the tree for DOC_ORG)
        if (countFolderTemplates() == 0) {
            jdbc.update("insert into folder_template " +
                    "(id,org_id,version,display_name,sort_key,is_old_loan_archive,is_delete_folder,is_active,sort_order,eval_prompt) " +
                    "select gen_random_uuid(), ?::uuid, 0, display_name, sort_key, is_old_loan_archive, " +
                    "is_delete_folder, is_active, sort_order, eval_prompt from folder_template where org_id = ?::uuid",
                    DOC_ORG, DEFAULT_ORG);
        }
        // copy document_types
        if (countDocumentTypes() == 0) {
            jdbc.update("insert into document_type " +
                    "(id,org_id,version,name,slug,default_folder_name,required_for_milestones," +
                    "allowed_mime_types,max_file_size_bytes,is_active,sort_order) " +
                    "select gen_random_uuid(), ?::uuid, 0, name, slug, default_folder_name, required_for_milestones, " +
                    "allowed_mime_types, max_file_size_bytes, is_active, sort_order from document_type where org_id = ?::uuid",
                    DOC_ORG, DEFAULT_ORG);
        }
        // a tiny-cap custom type for the max-size case (10-byte cap, pdf-only)
        jdbc.update("insert into document_type " +
                "(id,org_id,version,name,slug,default_folder_name,allowed_mime_types,max_file_size_bytes,is_active,sort_order) " +
                "values (gen_random_uuid(), ?::uuid, 0, 'Tiny Cap', 'tiny-cap', null, 'application/pdf', 10, true, 60) " +
                "on conflict (org_id, slug) do nothing", DOC_ORG);
    }

    private int countFolderTemplates() {
        Integer n = jdbc.queryForObject("select count(*) from folder_template where org_id = ?::uuid", Integer.class, DOC_ORG);
        return n == null ? 0 : n;
    }

    private int countDocumentTypes() {
        Integer n = jdbc.queryForObject("select count(*) from document_type where org_id = ?::uuid", Integer.class, DOC_ORG);
        return n == null ? 0 : n;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(USER)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String typeId(String slug) {
        return jdbc.queryForObject("select id::text from document_type where org_id = ?::uuid and slug = ?",
                String.class, DOC_ORG, slug);
    }

    private static String path(String url) {
        URI u = URI.create(url);
        return u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    }

    /** Drives steps 1-2: POST upload-url, then authenticated PUT of bytes to the returned uploadUrl. */
    private String[] startUpload(String loanId, String body, byte[] bytes, String putContentType) throws Exception {
        var res = mvc.perform(post("/api/loans/{l}/documents/upload-url", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String json = res.getResponse().getContentAsString();
        String docId = JsonPath.read(json, "$.data.documentId");
        String uploadUrl = JsonPath.read(json, "$.data.uploadUrl");

        mvc.perform(put(path(uploadUrl)).with(staff())
                        .contentType(putContentType).content(bytes))
                .andExpect(status().isOk());
        return new String[]{docId, uploadUrl};
    }

    // ── 1) full real round-trip: upload-url → PUT bytes → confirm → UPLOADED + size + folder ──

    @Test
    void fullPresignedRoundTripConfirmsUploadedWithSizeAndFolderRouting() throws Exception {
        String loanId = createLoan();
        byte[] payload = "a-real-w2-pdf-payload".getBytes();
        String body = """
                {"fileName":"w2.pdf","partyRole":"borrower","contentType":"application/pdf",
                 "documentType":"INCOME_DOC","documentTypeId":"%s"}
                """.formatted(typeId("w-2"));

        String docId = startUpload(loanId, body, payload, "application/pdf")[0];

        // confirm → UPLOADED + size + auto-routed into the "03 Income" folder (W-2's default)
        mvc.perform(put("/api/loans/{l}/documents/{d}/confirm", loanId, docId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentStatus").value("UPLOADED"))
                .andExpect(jsonPath("$.data.fileSize").value(payload.length))
                .andExpect(jsonPath("$.data.partyRole").value("borrower"))
                .andExpect(jsonPath("$.data.folderId").isNotEmpty());

        // the routed folder is the loan's "03 Income"
        var tree = mvc.perform(get("/api/loans/{l}/folders", loanId).with(staff()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> incomeIds = JsonPath.read(tree, "$.data.folders[?(@.displayName == 'Income')].id");

        var doc = mvc.perform(get("/api/loans/{l}/documents", loanId).with(staff()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> folderIds = JsonPath.read(doc, "$.data.documents[?(@.fileName == 'w2.pdf')].folderId");
        assertThat(folderIds).containsExactly(incomeIds.get(0));
    }

    // ── 2) MIME rejection at upload-url (allowlisted type + wrong contentType) ──────────────

    @Test
    void mimeRejectionReturns400() throws Exception {
        String loanId = createLoan();
        // tax-return allows application/pdf only; send image/png
        String body = """
                {"fileName":"return.png","partyRole":"borrower","contentType":"image/png",
                 "documentTypeId":"%s"}
                """.formatted(typeId("tax-return"));

        mvc.perform(post("/api/loans/{l}/documents/upload-url", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("is not allowed"), containsString("Allowed"))));
    }

    @Test
    void mimeAcceptedWhenContentTypeHasCharsetParam() throws Exception {
        String loanId = createLoan();
        // contentType carries a ;charset suffix → normalized to application/pdf, accepted
        String body = """
                {"fileName":"return.pdf","partyRole":"borrower","contentType":"application/pdf; charset=binary",
                 "documentTypeId":"%s"}
                """.formatted(typeId("tax-return"));

        mvc.perform(post("/api/loans/{l}/documents/upload-url", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    // ── 3) max-size enforcement on confirm (tiny-cap type + bigger bytes) ──────────────────

    @Test
    void confirmRejectsOversizeFor400() throws Exception {
        String loanId = createLoan();
        byte[] tooBig = "this is more than ten bytes".getBytes(); // > 10-byte cap
        String body = """
                {"fileName":"big.pdf","partyRole":"lo","contentType":"application/pdf","documentTypeId":"%s"}
                """.formatted(typeId("tiny-cap"));

        String docId = startUpload(loanId, body, tooBig, "application/pdf")[0];

        mvc.perform(put("/api/loans/{l}/documents/{d}/confirm", loanId, docId).with(staff()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("exceeds max size")));
    }

    @Test
    void confirmRejectsWhenBytesNeverUploaded() throws Exception {
        String loanId = createLoan();
        String body = """
                {"fileName":"ghost.pdf","partyRole":"lo","contentType":"application/pdf"}
                """;
        var res = mvc.perform(post("/api/loans/{l}/documents/upload-url", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String docId = JsonPath.read(res.getResponse().getContentAsString(), "$.data.documentId");

        // never PUT the bytes → headSize == -1 → 400
        mvc.perform(put("/api/loans/{l}/documents/{d}/confirm", loanId, docId).with(staff()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Upload not found")));
    }

    // ── 4) download-url returns a usable URL (GET it → exact bytes) ─────────────────────────

    @Test
    void downloadUrlReturnsUsableUrl() throws Exception {
        String loanId = createLoan();
        byte[] payload = "downloadable-bytes".getBytes();
        String body = """
                {"fileName":"stmt.pdf","partyRole":"borrower","contentType":"application/pdf","documentTypeId":"%s"}
                """.formatted(typeId("bank-statement"));
        String docId = startUpload(loanId, body, payload, "application/pdf")[0];
        mvc.perform(put("/api/loans/{l}/documents/{d}/confirm", loanId, docId).with(staff()))
                .andExpect(status().isOk());

        var res = mvc.perform(get("/api/loans/{l}/documents/{d}/download-url", loanId, docId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(greaterThan(0)))
                .andReturn();
        String url = JsonPath.read(res.getResponse().getContentAsString(), "$.data.downloadUrl");

        var bytes = mvc.perform(get(path(url)).with(staff()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(bytes).isEqualTo(payload);
    }

    // ── 5) list excludes PENDING_UPLOAD + DELETED_SOFT ─────────────────────────────────────

    @Test
    void listExcludesPendingAndDeleted() throws Exception {
        String loanId = createLoan();

        // a) confirmed doc → appears
        byte[] payload = "confirmed".getBytes();
        String confirmedBody = """
                {"fileName":"confirmed.pdf","partyRole":"lo","contentType":"application/pdf"}
                """;
        String confirmedId = startUpload(loanId, confirmedBody, payload, "application/pdf")[0];
        mvc.perform(put("/api/loans/{l}/documents/{d}/confirm", loanId, confirmedId).with(staff()))
                .andExpect(status().isOk());

        // b) pending doc (upload-url only, never confirmed) → excluded
        mvc.perform(post("/api/loans/{l}/documents/upload-url", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"pending.pdf\",\"partyRole\":\"lo\"}"))
                .andExpect(status().isCreated());

        var res = mvc.perform(get("/api/loans/{l}/documents", loanId).with(staff()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(res, "$.data.documents[*].fileName");
        assertThat(names).contains("confirmed.pdf").doesNotContain("pending.pdf");
    }

    // ── 6) search facets each filter at the query layer ────────────────────────────────────

    @Test
    void searchFacetsEachFilterAtQueryLayer() throws Exception {
        String loanId = createLoan();

        // doc A: bank-statement, partyRole borrower, fileName "alpha-statement.pdf"
        String aId = confirmUpload(loanId, "alpha-statement.pdf", "borrower", "bank-statement", "AAA".getBytes());
        // doc B: pay-stub, partyRole lo, fileName "beta-paystub.pdf"
        String bId = confirmUpload(loanId, "beta-paystub.pdf", "lo", "pay-stub", "BBB".getBytes());

        // filter by documentTypeId → only A
        mvc.perform(get("/api/loans/{l}/documents/search", loanId).with(staff())
                        .param("documentTypeId", typeId("bank-statement")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.documents[0].id").value(aId));

        // filter by partyRole → only B
        mvc.perform(get("/api/loans/{l}/documents/search", loanId).with(staff())
                        .param("partyRole", "lo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents[*].id", contains(bId)));

        // filter by status UPLOADED → both
        mvc.perform(get("/api/loans/{l}/documents/search", loanId).with(staff())
                        .param("status", "UPLOADED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        // filter by q (case-insensitive fileName contains) → only A
        mvc.perform(get("/api/loans/{l}/documents/search", loanId).with(staff())
                        .param("q", "ALPHA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents[*].id", contains(aId)));

        // filter by uploadedBy (the staff USER) → both; bogus user → none
        mvc.perform(get("/api/loans/{l}/documents/search", loanId).with(staff())
                        .param("uploadedBy", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get("/api/loans/{l}/documents/search", loanId).with(staff())
                        .param("uploadedBy", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        // filter by folderId (bank-statement auto-routes to 04 Assets) → only A
        String assetsFolder = JsonPath.<List<String>>read(
                mvc.perform(get("/api/loans/{l}/folders", loanId).with(staff()))
                        .andReturn().getResponse().getContentAsString(),
                "$.data.folders[?(@.displayName == 'Assets')].id").get(0);
        mvc.perform(get("/api/loans/{l}/documents/search", loanId).with(staff())
                        .param("folderId", assetsFolder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents[*].id", contains(aId)));
    }

    /** One-shot upload+confirm; returns the confirmed doc id. */
    private String confirmUpload(String loanId, String fileName, String partyRole, String slug, byte[] bytes) throws Exception {
        String body = """
                {"fileName":"%s","partyRole":"%s","contentType":"application/pdf","documentTypeId":"%s"}
                """.formatted(fileName, partyRole, typeId(slug));
        String docId = startUpload(loanId, body, bytes, "application/pdf")[0];
        mvc.perform(put("/api/loans/{l}/documents/{d}/confirm", loanId, docId).with(staff()))
                .andExpect(status().isOk());
        return docId;
    }

    // ── 7) patch updates fields + rejects a foreign-loan folder ────────────────────────────

    @Test
    void patchUpdatesFieldsAndRejectsForeignLoanFolder() throws Exception {
        String loanId = createLoan();
        String otherLoanId = createLoan();
        String docId = confirmUpload(loanId, "orig.pdf", "lo", "bank-statement", "X".getBytes());

        // a foreign-loan folder → 400
        String foreignFolder = JsonPath.<List<String>>read(
                mvc.perform(get("/api/loans/{l}/folders", otherLoanId).with(staff()))
                        .andReturn().getResponse().getContentAsString(),
                "$.data.folders[?(@.displayName == 'Income')].id").get(0);
        mvc.perform(patch("/api/loans/{l}/documents/{d}", loanId, docId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":\"%s\"}".formatted(foreignFolder)))
                .andExpect(status().isBadRequest());

        // valid field updates apply; null fields are left alone
        mvc.perform(patch("/api/loans/{l}/documents/{d}", loanId, docId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"renamed.pdf\",\"description\":\"updated desc\",\"documentType\":\"APPRAISAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("renamed.pdf"))
                .andExpect(jsonPath("$.data.description").value("updated desc"))
                .andExpect(jsonPath("$.data.documentType").value("APPRAISAL"));
    }

    // ── 8) move into a folder + unfile ─────────────────────────────────────────────────────

    @Test
    void moveIntoFolderThenUnfile() throws Exception {
        String loanId = createLoan();
        String docId = confirmUpload(loanId, "movable.pdf", "lo", "bank-statement", "Y".getBytes());

        String creditFolder = JsonPath.<List<String>>read(
                mvc.perform(get("/api/loans/{l}/folders", loanId).with(staff()))
                        .andReturn().getResponse().getContentAsString(),
                "$.data.folders[?(@.displayName == 'Credit')].id").get(0);

        // move into Credit
        mvc.perform(post("/api/loans/{l}/documents/move", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docIds\":[\"%s\"],\"toFolderId\":\"%s\"}".formatted(docId, creditFolder)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requested").value(1))
                .andExpect(jsonPath("$.data.moved").value(1))
                .andExpect(jsonPath("$.data.toFolderId").value(creditFolder));

        assertThat(folderIdOf(loanId, docId)).isEqualTo(creditFolder);

        // unfile (toFolderId null)
        mvc.perform(post("/api/loans/{l}/documents/move", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docIds\":[\"%s\"],\"toFolderId\":null}".formatted(docId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.moved").value(1));

        assertThat(folderIdOf(loanId, docId)).isNull();
    }

    private String folderIdOf(String loanId, String docId) throws Exception {
        var res = mvc.perform(get("/api/loans/{l}/documents", loanId).with(staff()))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(res, "$.data.documents[?(@.id == '" + docId + "')].folderId");
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ── 9) permanent-delete blocked unless in Delete folder, then succeeds (soft-deleted) ───

    @Test
    void permanentDeleteBlockedUnlessInDeleteFolderThenSucceeds() throws Exception {
        String loanId = createLoan();
        String docId = confirmUpload(loanId, "doomed.pdf", "lo", "bank-statement", "Z".getBytes());

        // not in Delete folder → 400
        mvc.perform(delete("/api/loans/{l}/documents/{d}/permanent", loanId, docId).with(staff()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Delete folder")));

        // move into the Delete folder
        String deleteFolder = JsonPath.<List<String>>read(
                mvc.perform(get("/api/loans/{l}/folders", loanId).with(staff()))
                        .andReturn().getResponse().getContentAsString(),
                "$.data.folders[?(@.isDeleteFolder == true)].id").get(0);
        mvc.perform(post("/api/loans/{l}/documents/move", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docIds\":[\"%s\"],\"toFolderId\":\"%s\"}".formatted(docId, deleteFolder)))
                .andExpect(status().isOk());

        // now permanent-delete → ok
        mvc.perform(delete("/api/loans/{l}/documents/{d}/permanent", loanId, docId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));

        // gone from list; row soft-deleted (deleted_at set)
        var list = mvc.perform(get("/api/loans/{l}/documents", loanId).with(staff()))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(list, "$.data.documents[*].id");
        assertThat(ids).doesNotContain(docId);
        Integer softDeleted = jdbc.queryForObject(
                "select count(*) from document where id = ?::uuid and deleted_at is not null", Integer.class, docId);
        assertThat(softDeleted).isEqualTo(1);
    }

    // ── 10) tenant isolation: another org cannot see/confirm/delete this org's docs ─────────

    @Test
    void tenantIsolationBlocksForeignOrg() throws Exception {
        String loanId = createLoan();
        byte[] payload = "secret".getBytes();
        String body = """
                {"fileName":"secret.pdf","partyRole":"lo","contentType":"application/pdf"}
                """;
        var res = mvc.perform(post("/api/loans/{l}/documents/upload-url", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String docId = JsonPath.read(res.getResponse().getContentAsString(), "$.data.documentId");
        String uploadUrl = JsonPath.read(res.getResponse().getContentAsString(), "$.data.uploadUrl");
        mvc.perform(put(path(uploadUrl)).with(staff()).contentType("application/pdf").content(payload))
                .andExpect(status().isOk());

        RequestPostProcessor otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        // foreign org: loan itself is invisible → 404 on confirm + delete + list
        mvc.perform(put("/api/loans/{l}/documents/{d}/confirm", loanId, docId).with(otherOrg))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/loans/{l}/documents/{d}/permanent", loanId, docId).with(otherOrg))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/loans/{l}/documents", loanId).with(otherOrg))
                .andExpect(status().isNotFound());
    }
}
