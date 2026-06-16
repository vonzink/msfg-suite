package com.msfg.los.documents.web;

import com.msfg.los.documents.service.DocumentTypeCatalogService;
import com.msfg.los.documents.web.dto.DocumentTypeResponse;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only document-type catalog for any authenticated staff member (no role gate beyond
 * authentication — the {@code /api/**} → authenticated rule in SecurityConfig covers this).
 * Tenant-scoped: returns only the caller-org's active types (via {@code @TenantId}).
 */
@RestController
@RequestMapping("/api/document-types")
public class DocumentTypeController {

    private final DocumentTypeCatalogService service;

    public DocumentTypeController(DocumentTypeCatalogService service) {
        this.service = service;
    }

    /** Active types for the caller's org, ordered by sort_order ('other' last at 99). */
    @GetMapping
    public ApiResponse<DocumentTypesView> list() {
        List<DocumentTypeResponse> views = service.listActive().stream()
                .map(DocumentTypeResponse::from)
                .toList();
        return ApiResponse.ok(new DocumentTypesView(views.size(), views));
    }

    /** One type by slug, 404 if unknown in the caller's org. */
    @GetMapping("/{slug}")
    public ApiResponse<DocumentTypeResponse> getBySlug(@PathVariable String slug) {
        return ApiResponse.ok(DocumentTypeResponse.from(service.getBySlug(slug)));
    }

    /** List envelope {count, documentTypes:[...]}. Unique simple name for springdoc. */
    public record DocumentTypesView(int count, List<DocumentTypeResponse> documentTypes) {
    }
}
