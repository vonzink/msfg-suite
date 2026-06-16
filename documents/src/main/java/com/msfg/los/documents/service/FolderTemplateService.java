package com.msfg.los.documents.service;

import com.msfg.los.documents.domain.FolderTemplate;
import com.msfg.los.documents.repo.FolderTemplateRepository;
import com.msfg.los.documents.web.dto.UpsertFolderTemplateRequest;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Org-scoped folder-template catalog. All reads/writes are tenant-filtered via Hibernate
 * {@code @TenantId}; by-PK loads use {@code findByIdAndOrgId} so a foreign-org id resolves to 404.
 *
 * <p>{@code displayName} is the per-org unique key (DB {@code unique(org_id, display_name)}; the
 * app pre-check yields a friendly 400, the constraint is the backstop). Two app-enforced
 * <b>singletons</b> per org over the ACTIVE set: at most one {@code deleteFolder=true} and at most
 * one {@code oldLoanArchive=true} — a create/update that would make a second active special → 400.
 * The active Delete-folder template is <b>required</b>: it cannot be deactivated (→ 400), because the
 * per-loan folder tree materializes it as the system Delete folder (the permanent-delete gate).
 * {@code evalPrompt} is a Phase-4 AI column — pass-through, null clears.
 */
@Service
public class FolderTemplateService {

    static final String DELETE_REQUIRED_MESSAGE =
            "The Delete folder template is required and cannot be deactivated";

    private final FolderTemplateRepository repo;
    private final TenantContext tenantContext;

    public FolderTemplateService(FolderTemplateRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    // ── reads ────────────────────────────────────────────────────────────────────────────

    /** Active templates for the current org, ordered by sort_order. */
    @Transactional(readOnly = true)
    public List<FolderTemplate> listActive() {
        return repo.findByActiveTrueOrderBySortOrderAsc();
    }

    /** All templates (active + inactive) for the current org, ordered by sort_order. */
    @Transactional(readOnly = true)
    public List<FolderTemplate> listAll() {
        return repo.findAllByOrderBySortOrderAsc();
    }

    /** Template by id within the current org, 404 if missing or foreign-org. */
    @Transactional(readOnly = true)
    public FolderTemplate get(UUID id) {
        return load(id);
    }

    // ── admin writes ───────────────────────────────────────────────────────────────────────

    @Transactional
    public FolderTemplate create(UpsertFolderTemplateRequest req) {
        String name = requireName(req.displayName());
        if (repo.existsByDisplayName(name)) {
            throw nameCollision(name);
        }
        FolderTemplate t = new FolderTemplate();
        t.setDisplayName(name);
        applyFlags(t, req);
        validateSingletons(t);
        return repo.save(t);
    }

    @Transactional
    public FolderTemplate update(UUID id, UpsertFolderTemplateRequest req) {
        FolderTemplate t = load(id);
        String name = requireName(req.displayName());
        if (!name.equals(t.getDisplayName())) {
            repo.findByDisplayName(name)
                    .filter(other -> !other.getId().equals(t.getId()))
                    .ifPresent(other -> { throw nameCollision(name); });
            t.setDisplayName(name);
        }
        applyFlags(t, req);
        validateSingletons(t);
        return repo.save(t);
    }

    /**
     * Soft-delete: set is_active=false. Refuses the active Delete-folder template (it is required
     * for the per-loan tree's permanent-delete gate). Idempotent for any other template.
     */
    @Transactional
    public void deactivate(UUID id) {
        FolderTemplate t = load(id);
        if (t.isDeleteFolder() && t.isActive()) {
            throw new ValidationException(DELETE_REQUIRED_MESSAGE);
        }
        t.setActive(false);
        repo.save(t);
    }

    // ── singleton enforcement ──────────────────────────────────────────────────────────────

    /**
     * At most one ACTIVE delete-folder template and at most one ACTIVE old-loan-archive template
     * per org. Called after {@code applyFlags} but before persisting {@code candidate}; the
     * candidate may be new (no id) or an existing row being updated (its own id is excluded so a
     * no-op re-save of the existing special doesn't trip the check).
     */
    private void validateSingletons(FolderTemplate candidate) {
        if (candidate.isActive() && candidate.isDeleteFolder()) {
            repo.findByActiveTrueAndDeleteFolderTrue()
                    .filter(existing -> !existing.getId().equals(candidate.getId()))
                    .ifPresent(existing -> {
                        throw new ValidationException(
                                "An active Delete folder template already exists (only one is allowed per org)");
                    });
        }
        if (candidate.isActive() && candidate.isOldLoanArchive()) {
            repo.findByActiveTrueAndOldLoanArchiveTrue()
                    .filter(existing -> !existing.getId().equals(candidate.getId()))
                    .ifPresent(existing -> {
                        throw new ValidationException(
                                "An active Old Loan Archive template already exists (only one is allowed per org)");
                    });
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private FolderTemplate load(UUID id) {
        return repo.findByIdAndOrgId(id, tenantContext.requireOrgId())
                .orElseThrow(() -> new NotFoundException("Folder template", id));
    }

    private static void applyFlags(FolderTemplate t, UpsertFolderTemplateRequest req) {
        t.setSortKey(blankToNull(req.sortKey()));
        t.setOldLoanArchive(req.oldLoanArchiveOrDefault());
        t.setDeleteFolder(req.deleteFolderOrDefault());
        t.setActive(req.activeOrDefault());
        t.setSortOrder(req.sortOrderOrDefault());
        t.setEvalPrompt(blankToNull(req.evalPrompt())); // null clears the Phase-4 AI column
    }

    private static String requireName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new ValidationException("displayName is required");
        }
        return displayName.trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static ValidationException nameCollision(String name) {
        return new ValidationException("A folder template named \"" + name + "\" already exists");
    }
}
