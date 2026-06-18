package com.msfg.los.documents.web;

import com.msfg.los.documents.service.DocumentTypeCatalogService;
import com.msfg.los.documents.web.dto.DocumentTypeResponse;
import com.msfg.los.documents.web.dto.UpsertDocumentTypeRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for the org-scoped document-type catalog. Admin-gated exactly as the repo's other
 * admin controllers (e.g. {@code OrganizationController}): the {@code /api/admin/**} URL rule in
 * SecurityConfig requires {@code hasRole('PLATFORM_ADMIN')} — no method-level annotation. Edits
 * stay within the caller's org via {@code @TenantId}. Dup-slug → 400; deactivate is a soft DELETE.
 */
@RestController
@RequestMapping("/api/admin/document-types")
public class AdminDocumentTypeController {

    private final DocumentTypeCatalogService service;

    public AdminDocumentTypeController(DocumentTypeCatalogService service) {
        this.service = service;
    }

    /** All types (active + inactive) for the caller's org, sorted by sort_order. */
    @GetMapping
    public ApiResponse<List<DocumentTypeResponse>> list() {
        return ApiResponse.ok(service.listAll().stream()
                .map(DocumentTypeResponse::from)
                .toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentTypeResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(DocumentTypeResponse.from(service.get(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DocumentTypeResponse>> create(
            @Valid @RequestBody UpsertDocumentTypeRequest req) {
        var created = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(DocumentTypeResponse.from(created)));
    }

    @PutMapping("/{id}")
    public ApiResponse<DocumentTypeResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpsertDocumentTypeRequest req) {
        return ApiResponse.ok(DocumentTypeResponse.from(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
