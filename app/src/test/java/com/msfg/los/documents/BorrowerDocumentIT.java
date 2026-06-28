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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Cutover slice 1 — a borrower uploads documents into the SUITE (the system of record), so staff
 * processing/UW see them. Borrower-scoped seam at {@code /api/loans/{loanId}/borrower/documents}
 * mirrors the Stage-2 borrower-self application pattern: a linked BORROWER can upload / confirm /
 * list / download their OWN documents; staff see borrower uploads automatically in the existing DMS.
 *
 * <p>Setup copies the V18 folder_template + document_type seed into a dedicated org (so a fresh loan
 * auto-seeds the folder tree), creates the loan as the LO, adds a borrower, and links it to a Cognito
 * sub. The raw-bytes PUT to the presigned URL is done with the LO token — in production that PUT goes
 * straight to S3 (no app auth); locally it hits the internal receive endpoint, a transport detail
 * outside the borrower-authorization surface under test.
 */
class BorrowerDocumentIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static final String BD_ORG = "00000000-0000-0000-0000-0000000000c9";
    static final String LO = UUID.randomUUID().toString();

    @BeforeEach
    void seedOrg() {
        jdbc.update("insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,'bdoc-c9','bdoc-c9','ACTIVE','{}'::jsonb) on conflict (id) do nothing", BD_ORG);
        if (count("folder_template") == 0) {
            jdbc.update("insert into folder_template " +
                    "(id,org_id,version,display_name,sort_key,is_old_loan_archive,is_delete_folder,is_active,sort_order,eval_prompt) " +
                    "select gen_random_uuid(), ?::uuid, 0, display_name, sort_key, is_old_loan_archive, " +
                    "is_delete_folder, is_active, sort_order, eval_prompt from folder_template where org_id = ?::uuid",
                    BD_ORG, DEFAULT_ORG);
        }
        if (count("document_type") == 0) {
            jdbc.update("insert into document_type " +
                    "(id,org_id,version,name,slug,default_folder_name,required_for_milestones," +
                    "allowed_mime_types,max_file_size_bytes,is_active,sort_order) " +
                    "select gen_random_uuid(), ?::uuid, 0, name, slug, default_folder_name, required_for_milestones, " +
                    "allowed_mime_types, max_file_size_bytes, is_active, sort_order from document_type where org_id = ?::uuid",
                    BD_ORG, DEFAULT_ORG);
        }
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table + " where org_id = ?::uuid", Integer.class, BD_ORG);
        return n == null ? 0 : n;
    }

    // ── the win: a borrower uploads into the suite; staff see it; borrower lists + downloads own ──

    @Test
    void borrowerUploadsIntoSuite_staffSeesIt_borrowerListsAndDownloadsOwn() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId, "Bo");
        String sub = UUID.randomUUID().toString();
        linkUser(loanId, borrowerId, sub);
        RequestPostProcessor borrower = borrower(sub);

        byte[] payload = "borrower-paystub-bytes".getBytes();
        String body = """
                {"fileName":"paystub.pdf","contentType":"application/pdf","documentTypeId":"%s"}
                """.formatted(typeId("pay-stub"));

        // step 1 — borrower requests a presigned upload URL
        var up = mvc.perform(post("/api/loans/{l}/borrower/documents/upload-url", loanId).with(borrower)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String docId = JsonPath.read(up, "$.data.documentId");
        String uploadUrl = JsonPath.read(up, "$.data.uploadUrl");

        // step 2 — bytes to storage (LO token = transport stand-in for the S3 PUT)
        mvc.perform(put(path(uploadUrl)).with(lo()).contentType("application/pdf").content(payload))
                .andExpect(status().isOk());

        // step 3 — borrower confirms → UPLOADED, stamped as a borrower upload by this sub
        mvc.perform(put("/api/loans/{l}/borrower/documents/{d}/confirm", loanId, docId).with(borrower))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentStatus").value("UPLOADED"))
                .andExpect(jsonPath("$.data.partyRole").value("borrower"))
                .andExpect(jsonPath("$.data.uploadedBy").value(sub))
                .andExpect(jsonPath("$.data.fileSize").value(payload.length));

        // THE WIN — the staff DMS sees the borrower's upload automatically
        var staffList = mvc.perform(get("/api/loans/{l}/documents", loanId).with(lo()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(staffList, "$.data.documents[*].fileName")).contains("paystub.pdf");

        // borrower sees their OWN upload in their portal
        mvc.perform(get("/api/loans/{l}/borrower/documents", loanId).with(borrower))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents[*].id", hasItem(docId)));

        // borrower can download their own doc
        var dl = mvc.perform(get("/api/loans/{l}/borrower/documents/{d}/download-url", loanId, docId).with(borrower))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String downloadUrl = JsonPath.read(dl, "$.data.downloadUrl");
        byte[] got = mvc.perform(get(path(downloadUrl)).with(lo()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(payload);
    }

    // ── denials ──────────────────────────────────────────────────────────────────────────────

    @Test
    void unlinkedBorrower_isDenied() throws Exception {
        String loanId = createLoan();
        addBorrower(loanId, "Al"); // a borrower exists, but the probe sub is NOT linked

        mvc.perform(post("/api/loans/{l}/borrower/documents/upload-url", loanId)
                        .with(borrower(UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"x.pdf\",\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void borrower_cannotActOnADocTheyDidNotUpload() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId, "Bo");
        String sub = UUID.randomUUID().toString();
        linkUser(loanId, borrowerId, sub);

        // a STAFF-uploaded doc on the same loan
        String staffDoc = staffUpload(loanId, "internal-uw-note.pdf");

        // the linked borrower must NOT be able to download a doc they did not upload
        mvc.perform(get("/api/loans/{l}/borrower/documents/{d}/download-url", loanId, staffDoc).with(borrower(sub)))
                .andExpect(status().isForbidden());

        // ...and it must NOT appear in the borrower's own-documents list
        mvc.perform(get("/api/loans/{l}/borrower/documents", loanId).with(borrower(sub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents[?(@.id == '%s')]".formatted(staffDoc)).isEmpty());
    }

    @Test
    void borrower_cannotReConfirmToResetReviewStatus() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId, "Bo");
        String sub = UUID.randomUUID().toString();
        linkUser(loanId, borrowerId, sub);
        RequestPostProcessor borrower = borrower(sub);

        String body = """
                {"fileName":"w2.pdf","contentType":"application/pdf","documentTypeId":"%s"}
                """.formatted(typeId("w-2"));
        var up = mvc.perform(post("/api/loans/{l}/borrower/documents/upload-url", loanId).with(borrower)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String docId = JsonPath.read(up, "$.data.documentId");
        String uploadUrl = JsonPath.read(up, "$.data.uploadUrl");
        mvc.perform(put(path(uploadUrl)).with(lo()).contentType("application/pdf").content("w2".getBytes()))
                .andExpect(status().isOk());

        // first confirm succeeds (PENDING_UPLOAD → UPLOADED)
        mvc.perform(put("/api/loans/{l}/borrower/documents/{d}/confirm", loanId, docId).with(borrower))
                .andExpect(status().isOk());
        // re-confirming an already-confirmed doc is a 409 — a borrower cannot reset a review decision
        mvc.perform(put("/api/loans/{l}/borrower/documents/{d}/confirm", loanId, docId).with(borrower))
                .andExpect(status().isConflict());
    }

    @Test
    void borrower_cannotReachStaffDocumentEndpoints() throws Exception {
        String loanId = createLoan();
        String borrowerId = addBorrower(loanId, "Bo");
        String sub = UUID.randomUUID().toString();
        linkUser(loanId, borrowerId, sub);

        // the staff-only DMS surface stays closed to borrowers
        mvc.perform(get("/api/loans/{l}/documents", loanId).with(borrower(sub)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/loans/{l}/documents/upload-url", loanId).with(borrower(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"x.pdf\",\"partyRole\":\"borrower\"}"))
                .andExpect(status().isForbidden());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", BD_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private RequestPostProcessor borrower(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("org_id", BD_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_BORROWER"));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String addBorrower(String loanId, String first) throws Exception {
        var res = mvc.perform(post("/api/loans/{loanId}/borrowers", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"%s\",\"lastName\":\"Buyer\",\"primary\":true}".formatted(first)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private void linkUser(String loanId, String borrowerId, String userSub) throws Exception {
        mvc.perform(post("/api/loans/{loanId}/borrowers/{bid}/link-user", loanId, borrowerId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(userSub)))
                .andExpect(status().isOk());
    }

    /** Staff (LO) presigned upload+confirm; returns the confirmed doc id. */
    private String staffUpload(String loanId, String fileName) throws Exception {
        byte[] bytes = "staff-bytes".getBytes();
        String body = """
                {"fileName":"%s","partyRole":"lo","contentType":"application/pdf"}
                """.formatted(fileName);
        var res = mvc.perform(post("/api/loans/{l}/documents/upload-url", loanId).with(lo())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String docId = JsonPath.read(res.getResponse().getContentAsString(), "$.data.documentId");
        String uploadUrl = JsonPath.read(res.getResponse().getContentAsString(), "$.data.uploadUrl");
        mvc.perform(put(path(uploadUrl)).with(lo()).contentType("application/pdf").content(bytes))
                .andExpect(status().isOk());
        mvc.perform(put("/api/loans/{l}/documents/{d}/confirm", loanId, docId).with(lo()))
                .andExpect(status().isOk());
        return docId;
    }

    private String typeId(String slug) {
        return jdbc.queryForObject("select id::text from document_type where org_id = ?::uuid and slug = ?",
                String.class, BD_ORG, slug);
    }

    private static String path(String url) {
        URI u = URI.create(url);
        return u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    }
}
