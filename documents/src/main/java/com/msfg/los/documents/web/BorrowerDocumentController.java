package com.msfg.los.documents.web;

import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.service.BorrowerDocumentService;
import com.msfg.los.documents.web.dto.BorrowerUploadUrlRequest;
import com.msfg.los.documents.web.dto.DocumentListResponse;
import com.msfg.los.documents.web.dto.DocumentResponse;
import com.msfg.los.documents.web.dto.DownloadUrlResponse;
import com.msfg.los.documents.web.dto.UploadUrlResponse;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Borrower-self document endpoints — a borrower linked to a loan uploads / confirms / lists /
 * downloads THEIR OWN documents into the suite DMS (the system of record). Separate from the
 * staff-only {@link DocumentController} ({@code /api/loans/{loanId}/documents}); authorization is the
 * borrower-on-loan + own-document check in {@link BorrowerDocumentService}, wired to the
 * {@code STAFF_AND_BORROWER} allowlist in SecurityConfig.
 */
@RestController
@RequestMapping("/api/loans/{loanId}/borrower/documents")
public class BorrowerDocumentController {

    private final BorrowerDocumentService service;

    public BorrowerDocumentController(BorrowerDocumentService service) {
        this.service = service;
    }

    @PostMapping("/upload-url")
    public ResponseEntity<ApiResponse<UploadUrlResponse>> uploadUrl(
            @PathVariable UUID loanId,
            @Valid @RequestBody BorrowerUploadUrlRequest req) {
        var u = service.uploadUrl(loanId, req);
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

    @GetMapping
    public ApiResponse<DocumentListResponse> list(@PathVariable UUID loanId) {
        List<DocumentResponse> docs = service.list(loanId)
                .stream().map(DocumentResponse::from).toList();
        return ApiResponse.ok(DocumentListResponse.of(docs));
    }

    @GetMapping("/{docId}/download-url")
    public ApiResponse<DownloadUrlResponse> downloadUrl(
            @PathVariable UUID loanId,
            @PathVariable UUID docId) {
        var d = service.downloadUrl(loanId, docId);
        return ApiResponse.ok(new DownloadUrlResponse(d.url(), d.expiresInSeconds()));
    }
}
