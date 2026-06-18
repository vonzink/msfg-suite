package com.msfg.los.documents.web;

import com.msfg.los.documents.domain.Folder;
import com.msfg.los.documents.service.FolderService;
import com.msfg.los.documents.web.dto.CreateFolderRequest;
import com.msfg.los.documents.web.dto.FolderResponse;
import com.msfg.los.documents.web.dto.FolderTreeResponse;
import com.msfg.los.documents.web.dto.RenameFolderRequest;
import com.msfg.los.documents.web.dto.SeedFoldersResponse;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loan-scoped folder tree. Staff-gated via {@link FolderService}'s {@code LoanAccessGuard}
 * (LO owner-scoped; PROCESSOR/UNDERWRITER/CLOSER org-wide; ADMIN). No borrower/agent path —
 * the cutover document subsystem is staff-only.
 */
@RestController
@RequestMapping("/api/loans/{loanId}/folders")
public class FolderController {

    private final FolderService service;

    public FolderController(FolderService service) {
        this.service = service;
    }

    /** Live folder tree; auto-seeds the default tree on first access. */
    @GetMapping
    public ApiResponse<FolderTreeResponse> tree(@PathVariable UUID loanId) {
        List<Folder> live = service.getTree(loanId);
        Map<UUID, String> evalPrompts = service.evalPromptsByTemplateId();
        List<FolderResponse> views = live.stream().map(f -> toView(f, evalPrompts)).toList();
        UUID rootId = live.stream()
                .filter(f -> f.getParentId() == null)
                .map(Folder::getId)
                .findFirst()
                .orElse(null);
        return ApiResponse.ok(new FolderTreeResponse(rootId, views.size(), views));
    }

    @PostMapping("/seed-defaults")
    public ApiResponse<SeedFoldersResponse> seedDefaults(@PathVariable UUID loanId) {
        Folder root = service.seedDefaults(loanId);
        return ApiResponse.ok(new SeedFoldersResponse(root.getId(), root.getDisplayName()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FolderResponse>> create(
            @PathVariable UUID loanId,
            @Valid @RequestBody CreateFolderRequest req) {
        Folder f = service.create(loanId, req.parentId(), req.displayName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(toView(f, service.evalPromptsByTemplateId())));
    }

    @PatchMapping("/{folderId}")
    public ApiResponse<FolderResponse> rename(
            @PathVariable UUID loanId,
            @PathVariable UUID folderId,
            @Valid @RequestBody RenameFolderRequest req) {
        Folder f = service.rename(loanId, folderId, req.displayName());
        return ApiResponse.ok(toView(f, service.evalPromptsByTemplateId()));
    }

    @DeleteMapping("/{folderId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID folderId) {
        service.softDelete(loanId, folderId);
        return ResponseEntity.noContent().build();
    }

    private static FolderResponse toView(Folder f, Map<UUID, String> evalPrompts) {
        String evalPrompt = f.getFolderTemplateId() == null ? null : evalPrompts.get(f.getFolderTemplateId());
        return FolderResponse.from(f, evalPrompt);
    }
}
