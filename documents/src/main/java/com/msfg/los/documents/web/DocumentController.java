package com.msfg.los.documents.web;

import com.msfg.los.documents.domain.DocumentType;
import com.msfg.los.documents.service.DocumentService;
import com.msfg.los.documents.web.dto.DocumentResponse;
import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.platform.web.PagedResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

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

    @GetMapping
    public ApiResponse<PagedResponse<DocumentResponse>> list(
            @PathVariable UUID loanId,
            @RequestParam(required = false) DocumentType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageResult = service.list(loanId, type, PageRequest.of(page, size));
        return ApiResponse.ok(PagedResponse.from(pageResult.map(DocumentResponse::from)));
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
