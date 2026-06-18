package com.msfg.los.documents.web;

import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentStatus;
import com.msfg.los.documents.domain.DocumentType;
import com.msfg.los.documents.service.DocumentService;
import com.msfg.los.documents.web.dto.DocumentListResponse;
import com.msfg.los.documents.web.dto.DocumentResponse;
import com.msfg.los.documents.web.dto.DocumentSearchResponse;
import com.msfg.los.documents.web.dto.DownloadUrlResponse;
import com.msfg.los.documents.web.dto.MoveDocumentsRequest;
import com.msfg.los.documents.web.dto.MoveDocumentsResult;
import com.msfg.los.documents.web.dto.PatchDocumentRequest;
import com.msfg.los.documents.web.dto.UploadUrlRequest;
import com.msfg.los.documents.web.dto.UploadUrlResponse;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loan-scoped document endpoints. Staff-gated via {@link DocumentService}'s {@code LoanAccessGuard}
 * (LO owner-scoped; PROCESSOR/UNDERWRITER/CLOSER org-wide; ADMIN). No borrower/agent path — the
 * cutover document subsystem is staff-only.
 *
 * <p>Phase-1 surface: 3-step presigned upload ({@code POST /upload-url} → client PUT →
 * {@code PUT /{id}/confirm}), presigned {@code GET /{id}/download-url}, query-side {@code GET /}
 * list + {@code GET /search}, and {@code PATCH /{id}} / {@code POST /move} /
 * {@code DELETE /{id}/permanent}. Review actions + status history are Task 6.
 *
 * <p>The legacy multipart {@code POST /} (used by generated-doc/test paths), binary
 * {@code GET /{id}/content}, {@code DELETE /{id}} and {@code POST /pre-approval} are retained.
 */
@RestController
@RequestMapping("/api/loans/{loanId}/documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    // ── 3-step presigned upload ──────────────────────────────────────────────────────────

    @PostMapping("/upload-url")
    public ResponseEntity<ApiResponse<UploadUrlResponse>> uploadUrl(
            @PathVariable UUID loanId,
            @Valid @RequestBody UploadUrlRequest req) {
        var u = service.createUploadUrl(loanId, req);
        var body = new UploadUrlResponse(
                u.doc().getId(),
                u.doc().getStorageKey(),
                u.uploadUrl(),
                u.contentType(),
                u.expiresInSeconds());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @PutMapping("/{docId}/confirm")
    public ApiResponse<DocumentResponse> confirm(
            @PathVariable UUID loanId,
            @PathVariable UUID docId) {
        Document doc = service.confirm(loanId, docId);
        return ApiResponse.ok(DocumentResponse.from(doc));
    }

    // ── download ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{docId}/download-url")
    public ApiResponse<DownloadUrlResponse> downloadUrl(
            @PathVariable UUID loanId,
            @PathVariable UUID docId) {
        var d = service.downloadUrl(loanId, docId);
        return ApiResponse.ok(new DownloadUrlResponse(d.url(), d.expiresInSeconds()));
    }

    // ── list + faceted search ──────────────────────────────────────────────────────────────

    @GetMapping
    public ApiResponse<DocumentListResponse> list(
            @PathVariable UUID loanId,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(defaultValue = "false") boolean unfiled,
            @RequestParam(defaultValue = "false") boolean atRoot) {
        List<DocumentResponse> docs = service.listConfirmed(loanId, folderId, unfiled, atRoot)
                .stream().map(DocumentResponse::from).toList();
        return ApiResponse.ok(DocumentListResponse.of(docs));
    }

    @GetMapping("/search")
    public ApiResponse<DocumentSearchResponse> search(
            @PathVariable UUID loanId,
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) UUID documentTypeId,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(required = false) String uploadedBy,
            @RequestParam(required = false) String partyRole,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        boolean folderIdProvided = folderId != null;
        Page<Document> result = service.search(
                loanId, status, documentTypeId, folderId, folderIdProvided,
                uploadedBy, partyRole, q, page, size);
        List<DocumentResponse> docs = result.getContent().stream().map(DocumentResponse::from).toList();
        return ApiResponse.ok(DocumentSearchResponse.from(result, docs));
    }

    // ── mutations ────────────────────────────────────────────────────────────────────────

    @PatchMapping("/{docId}")
    public ApiResponse<DocumentResponse> patch(
            @PathVariable UUID loanId,
            @PathVariable UUID docId,
            @RequestBody PatchDocumentRequest req) {
        Document doc = service.patch(loanId, docId, req);
        return ApiResponse.ok(DocumentResponse.from(doc));
    }

    @PostMapping("/move")
    public ApiResponse<MoveDocumentsResult> move(
            @PathVariable UUID loanId,
            @Valid @RequestBody MoveDocumentsRequest req) {
        int moved = service.move(loanId, req);
        return ApiResponse.ok(new MoveDocumentsResult(req.docIds().size(), moved, req.toFolderId()));
    }

    @DeleteMapping("/{docId}/permanent")
    public ApiResponse<Map<String, Object>> permanentDelete(
            @PathVariable UUID loanId,
            @PathVariable UUID docId) {
        service.permanentDelete(loanId, docId);
        return ApiResponse.ok(Map.of("ok", true, "docId", docId));
    }

    // ── legacy paths (retained) ────────────────────────────────────────────────────────────

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentResponse>> upload(
            @PathVariable UUID loanId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") DocumentType documentType,
            @RequestParam(value = "category", required = false) String category) throws IOException {
        var doc = service.upload(loanId, file, documentType, category);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(DocumentResponse.from(doc)));
    }

    @GetMapping("/{docId}/content")
    public ResponseEntity<byte[]> download(
            @PathVariable UUID loanId,
            @PathVariable UUID docId) {
        var result = service.load(loanId, docId);
        var doc = result.doc();
        String contentType = doc.getContentType() != null ? doc.getContentType() : "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.getFileName() + "\"")
                .body(result.bytes());
    }

    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID docId) {
        service.delete(loanId, docId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pre-approval")
    public ResponseEntity<ApiResponse<DocumentResponse>> generatePreApproval(
            @PathVariable UUID loanId) {
        var doc = service.generatePreApproval(loanId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(DocumentResponse.from(doc)));
    }
}
