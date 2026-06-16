package com.msfg.los.documents.web;

import com.msfg.los.documents.service.FolderTemplateService;
import com.msfg.los.documents.web.dto.FolderTemplateResponse;
import com.msfg.los.documents.web.dto.UpsertFolderTemplateRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for the org-scoped folder-template catalog. Admin-gated exactly as the repo's other
 * admin controllers (e.g. {@code OrganizationController}): the {@code /api/admin/**} URL rule in
 * SecurityConfig requires {@code hasRole('PLATFORM_ADMIN')} — no method-level annotation.
 * Tenant-scoped via {@code @TenantId}. Dup display_name → 400; ≤1 active Delete + ≤1 active
 * Old-Loan-Archive singletons enforced; the active Delete template cannot be deactivated (→ 400).
 */
@RestController
@RequestMapping("/api/admin/folder-templates")
public class AdminFolderTemplateController {

    private final FolderTemplateService service;

    public AdminFolderTemplateController(FolderTemplateService service) {
        this.service = service;
    }

    /** All templates (active + inactive) for the caller's org, sorted by sort_order. */
    @GetMapping
    public ApiResponse<List<FolderTemplateResponse>> list() {
        return ApiResponse.ok(service.listAll().stream()
                .map(FolderTemplateResponse::from)
                .toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<FolderTemplateResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(FolderTemplateResponse.from(service.get(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FolderTemplateResponse>> create(
            @Valid @RequestBody UpsertFolderTemplateRequest req) {
        var created = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(FolderTemplateResponse.from(created)));
    }

    @PutMapping("/{id}")
    public ApiResponse<FolderTemplateResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpsertFolderTemplateRequest req) {
        return ApiResponse.ok(FolderTemplateResponse.from(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
