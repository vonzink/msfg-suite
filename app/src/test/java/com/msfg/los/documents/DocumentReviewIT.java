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
 * End-to-end ITs for the Phase-1 document review state machine (Task 6): generic {@code PUT /status}
 * edges, accept/reject/request-revision (notes rules + reviewer-field side effects), illegal
 * transitions → 409 with the valid-transitions message, bulk-review per-doc failure collection, and
 * ordered status-history with tenant isolation.
 *
 * <p>Mirrors {@link DocumentLifecycleIT}'s DOC_ORG seeding + staff-JWT + 3-step upload helpers so a
 * confirmed doc starts in {@code UPLOADED} (the real entrypoint to the machine).
 */
class DocumentReviewIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    static final String USER = UUID.randomUUID().toString();
    static final String DOC_ORG = "00000000-0000-0000-0000-0000000000d6";

    private RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject(USER).claim("org_id", DOC_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    @BeforeEach
    void seedOrg() {
        jdbc.update("insert into organization (id,version,name,slug,status,settings) " +
                "values (?::uuid,0,'doc-d6','doc-d6','ACTIVE','{}'::jsonb) on conflict (id) do nothing", DOC_ORG);
        if (count("folder_template") == 0) {
            jdbc.update("insert into folder_template " +
                    "(id,org_id,version,display_name,sort_key,is_old_loan_archive,is_delete_folder,is_active,sort_order,eval_prompt) " +
                    "select gen_random_uuid(), ?::uuid, 0, display_name, sort_key, is_old_loan_archive, " +
                    "is_delete_folder, is_active, sort_order, eval_prompt from folder_template where org_id = ?::uuid",
                    DOC_ORG, DEFAULT_ORG);
        }
        if (count("document_type") == 0) {
            jdbc.update("insert into document_type " +
                    "(id,org_id,version,name,slug,default_folder_name,required_for_milestones," +
                    "allowed_mime_types,max_file_size_bytes,is_active,sort_order) " +
                    "select gen_random_uuid(), ?::uuid, 0, name, slug, default_folder_name, required_for_milestones, " +
                    "allowed_mime_types, max_file_size_bytes, is_active, sort_order from document_type where org_id = ?::uuid",
                    DOC_ORG, DEFAULT_ORG);
        }
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table + " where org_id = ?::uuid", Integer.class, DOC_ORG);
        return n == null ? 0 : n;
    }

    // ── helpers (mirror DocumentLifecycleIT) ─────────────────────────────────────────────────

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

    /** Upload + confirm a doc → returns its id in {@code UPLOADED}. */
    private String confirmUpload(String loanId, String fileName) throws Exception {
        String body = """
                {"fileName":"%s","partyRole":"borrower","contentType":"application/pdf","documentTypeId":"%s"}
                """.formatted(fileName, typeId("bank-statement"));
        var res = mvc.perform(post("/api/loans/{l}/documents/upload-url", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String json = res.getResponse().getContentAsString();
        String docId = JsonPath.read(json, "$.data.documentId");
        String uploadUrl = JsonPath.read(json, "$.data.uploadUrl");
        mvc.perform(put(path(uploadUrl)).with(staff()).contentType("application/pdf").content(fileName.getBytes()))
                .andExpect(status().isOk());
        mvc.perform(put("/api/loans/{l}/documents/{d}/confirm", loanId, docId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentStatus").value("UPLOADED"));
        return docId;
    }

    /** UPLOADED → READY_FOR_REVIEW via the generic PUT /status. */
    private void toReadyForReview(String loanId, String docId) throws Exception {
        mvc.perform(put("/api/loans/{l}/documents/{d}/status", loanId, docId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY_FOR_REVIEW\",\"note\":\"queued\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentStatus").value("READY_FOR_REVIEW"));
    }

    // ── 1) confirm → PUT /status → accept; reviewer fields + ordered history ──────────────────

    @Test
    void acceptSetsReviewerFieldsAndAppendsOrderedHistory() throws Exception {
        String loanId = createLoan();
        String docId = confirmUpload(loanId, "accept-me.pdf");

        toReadyForReview(loanId, docId);

        mvc.perform(post("/api/loans/{l}/documents/{d}/accept", loanId, docId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"looks good\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.reviewedBy").value(USER))
                .andExpect(jsonPath("$.data.reviewerNotes").value("looks good"))
                .andExpect(jsonPath("$.data.reviewedAt").isNotEmpty());

        // history has at least the two transitions, oldest-first
        var hist = mvc.perform(get("/api/loans/{l}/documents/{d}/status-history", loanId, docId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.docId").value(docId))
                .andReturn().getResponse().getContentAsString();
        List<String> statuses = JsonPath.read(hist, "$.data.history[*].status");
        assertThat(statuses).containsSubsequence("READY_FOR_REVIEW", "ACCEPTED");
        List<String> by = JsonPath.read(hist, "$.data.history[*].transitionedBy");
        assertThat(by).contains(USER);
    }

    // ── 2) reject: notes required (400 without) then 200 with → REJECTED ──────────────────────

    @Test
    void rejectRequiresNotes() throws Exception {
        String loanId = createLoan();
        String docId = confirmUpload(loanId, "reject-me.pdf");
        toReadyForReview(loanId, docId);

        // no notes → 400
        mvc.perform(post("/api/loans/{l}/documents/{d}/reject", loanId, docId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("notes are required")));

        // with notes → 200 REJECTED
        mvc.perform(post("/api/loans/{l}/documents/{d}/reject", loanId, docId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"blurry scan\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewerNotes").value("blurry scan"));
    }

    // ── 3) request-revision: notes required then → NEEDS_BORROWER_ACTION ──────────────────────

    @Test
    void requestRevisionRequiresNotes() throws Exception {
        String loanId = createLoan();
        String docId = confirmUpload(loanId, "revise-me.pdf");
        toReadyForReview(loanId, docId);

        mvc.perform(post("/api/loans/{l}/documents/{d}/request-revision", loanId, docId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("notes are required")));

        mvc.perform(post("/api/loans/{l}/documents/{d}/request-revision", loanId, docId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"need 2nd page\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentStatus").value("NEEDS_BORROWER_ACTION"))
                .andExpect(jsonPath("$.data.reviewerNotes").value("need 2nd page"));
    }

    // ── 4) illegal transition → 409 with valid-transitions message ────────────────────────────

    @Test
    void illegalGenericTransitionReturns409WithValidTransitions() throws Exception {
        String loanId = createLoan();
        String docId = confirmUpload(loanId, "stuck.pdf"); // UPLOADED

        // UPLOADED → ACCEPTED is not a legal edge → 409
        mvc.perform(put("/api/loans/{l}/documents/{d}/status", loanId, docId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_STATUS_CONFLICT"))
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("Cannot transition document from UPLOADED to ACCEPTED"),
                        containsString("Valid transitions"),
                        containsString("READY_FOR_REVIEW"))));
    }

    @Test
    void acceptFromUploadedReturns409() throws Exception {
        String loanId = createLoan();
        String docId = confirmUpload(loanId, "early-accept.pdf"); // UPLOADED, not yet READY_FOR_REVIEW

        mvc.perform(post("/api/loans/{l}/documents/{d}/accept", loanId, docId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_STATUS_CONFLICT"));
    }

    // ── 5) bulk-review: [reviewable, already-terminal] → succeeded/failed split, batch not aborted ─

    @Test
    void bulkReviewCollectsPerDocFailuresWithoutAbortingBatch() throws Exception {
        String loanId = createLoan();
        String good = confirmUpload(loanId, "bulk-good.pdf");
        toReadyForReview(loanId, good);

        // make a terminal doc: confirm → DELETE folder → permanent-delete (DELETED_SOFT, no out-edges)
        String dead = confirmUpload(loanId, "bulk-dead.pdf");
        String deleteFolder = JsonPath.<List<String>>read(
                mvc.perform(get("/api/loans/{l}/folders", loanId).with(staff()))
                        .andReturn().getResponse().getContentAsString(),
                "$.data.folders[?(@.isDeleteFolder == true)].id").get(0);
        mvc.perform(post("/api/loans/{l}/documents/move", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docIds\":[\"%s\"],\"toFolderId\":\"%s\"}".formatted(dead, deleteFolder)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/loans/{l}/documents/{d}/permanent", loanId, dead).with(staff()))
                .andExpect(status().isOk());

        // bulk accept over [good (READY_FOR_REVIEW), dead (DELETED_SOFT → skipped by live filter)]
        UUID missing = UUID.randomUUID();
        mvc.perform(post("/api/loans/{l}/documents/bulk-review", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPTED\",\"docIds\":[\"%s\",\"%s\",\"%s\"]}"
                                .formatted(good, dead, missing)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requested").value(3))
                .andExpect(jsonPath("$.data.succeeded").value(1))
                .andExpect(jsonPath("$.data.failed").value(2))
                .andExpect(jsonPath("$.data.decision").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.failures[*].docId", hasItems(dead, missing.toString())));

        // the good doc actually transitioned (batch was not aborted by the failures)
        mvc.perform(get("/api/loans/{l}/documents/{d}/status-history", loanId, good).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history[*].status", hasItem("ACCEPTED")));
    }

    @Test
    void bulkReviewRejectRequiresNotes() throws Exception {
        String loanId = createLoan();
        String docId = confirmUpload(loanId, "bulk-reject.pdf");
        toReadyForReview(loanId, docId);

        mvc.perform(post("/api/loans/{l}/documents/bulk-review", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECTED\",\"docIds\":[\"%s\"]}".formatted(docId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("notes are required")));
    }

    @Test
    void bulkReviewRejectsNonReviewDecision() throws Exception {
        String loanId = createLoan();
        String docId = confirmUpload(loanId, "bulk-bad-decision.pdf");

        mvc.perform(post("/api/loans/{l}/documents/bulk-review", loanId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ARCHIVED\",\"docIds\":[\"%s\"]}".formatted(docId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("ACCEPTED, REJECTED, NEEDS_BORROWER_ACTION")));
    }

    // ── 6) status-history ordered + tenant isolation (foreign org → 404) ──────────────────────

    @Test
    void statusHistoryIsTenantIsolated() throws Exception {
        String loanId = createLoan();
        String docId = confirmUpload(loanId, "isolated.pdf");
        toReadyForReview(loanId, docId);

        RequestPostProcessor otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        // foreign org: loan invisible → 404 on the history + the review actions
        mvc.perform(get("/api/loans/{l}/documents/{d}/status-history", loanId, docId).with(otherOrg))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/loans/{l}/documents/{d}/accept", loanId, docId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/loans/{l}/documents/{d}/status", loanId, docId).with(otherOrg)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownStatusNameReturns400() throws Exception {
        String loanId = createLoan();
        String docId = confirmUpload(loanId, "bad-status.pdf");

        mvc.perform(put("/api/loans/{l}/documents/{d}/status", loanId, docId).with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_A_REAL_STATUS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unknown document status")));
    }
}
