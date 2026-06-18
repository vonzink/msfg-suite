package com.msfg.los.documents.service;

import com.msfg.los.documents.domain.Folder;
import com.msfg.los.documents.domain.FolderTemplate;
import com.msfg.los.documents.repo.FolderRepository;
import com.msfg.los.documents.repo.FolderTemplateRepository;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.service.PrimaryBorrowerNameResolver;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loan-scoped folder tree. Mirrors mortgage-app's FolderService behavior (root + per-loan
 * materialization of the org's folder templates, user CRUD, soft-delete) adapted to the
 * multi-tenant + staff-only msfg-suite conventions:
 *
 *  - Every read/write is staff-gated via {@link LoanAccessGuard} after fetching the loan
 *    through {@link LoanService} (cross-module reads go through a SERVICE, never another
 *    module's repository — an ArchUnit test enforces this).
 *  - Tenant-scoped loads use {@code findByIdAndOrgId} (never bare {@code findById}); every
 *    by-id op re-verifies {@code folder.loanId == loanId}.
 *  - Sibling uniqueness is case-insensitive via {@code name_normalized = lower(trim(name))};
 *    the app pre-check yields a friendly 400, the DB partial unique indexes are the backstop.
 */
@Service
public class FolderService {

    private final FolderRepository folders;
    private final FolderTemplateRepository templates;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;
    private final PrimaryBorrowerNameResolver borrowerNameResolver;

    public FolderService(FolderRepository folders,
                         FolderTemplateRepository templates,
                         LoanService loanService,
                         LoanAccessGuard accessGuard,
                         TenantContext tenantContext,
                         PrimaryBorrowerNameResolver borrowerNameResolver) {
        this.folders = folders;
        this.templates = templates;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
        this.borrowerNameResolver = borrowerNameResolver;
    }

    // ── reads ──────────────────────────────────────────────────────────────────────────

    /**
     * Live folder tree for a loan, root first then by (sort_key, display_name). Auto-seeds
     * the default tree on first access so a fresh loan always returns a populated tree.
     */
    @Transactional
    public List<Folder> getTree(UUID loanId) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);
        ensureSeeded(loanId, loan);
        return orderedTree(loanId);
    }

    private List<Folder> orderedTree(UUID loanId) {
        List<Folder> live = folders.findByLoanIdAndDeletedAtIsNullOrderBySortKeyAscDisplayNameAsc(loanId);
        // Root first (parentId null), then by sort_key (nulls last), then display_name.
        return live.stream()
                .sorted(Comparator
                        .comparing((Folder f) -> f.getParentId() != null) // false (root) sorts first
                        .thenComparing(f -> f.getSortKey() == null ? "￿" : f.getSortKey())
                        .thenComparing(Folder::getDisplayName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Eval prompts for the org's active templates, keyed by templateId (for per-tree resolution). */
    @Transactional(readOnly = true)
    public Map<UUID, String> evalPromptsByTemplateId() {
        Map<UUID, String> out = new HashMap<>();
        for (FolderTemplate t : templates.findByActiveTrueOrderBySortOrderAsc()) {
            if (t.getEvalPrompt() != null) out.put(t.getId(), t.getEvalPrompt());
        }
        return out;
    }

    // ── seeding ────────────────────────────────────────────────────────────────────────

    /**
     * Public seed entrypoint: returns the loan's root id + name, creating the default tree
     * if absent. Idempotent.
     */
    @Transactional
    public Folder seedDefaults(UUID loanId) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);
        return ensureSeeded(loanId, loan);
    }

    /**
     * Find-or-create the single live root (parentId null, system), then materialize each
     * ACTIVE org folder_template (ordered by sort_order) as a system folder under root if no
     * live sibling already carries that normalized name. Idempotent; collisions skip silently.
     * If the org has zero templates, only the root is created (the documented fallback).
     *
     * @return the live root folder.
     */
    @Transactional
    public Folder ensureSeeded(UUID loanId, Loan loan) {
        Folder root = folders.findByLoanIdAndParentIdIsNullAndDeletedAtIsNull(loanId)
                .orElseGet(() -> createRoot(loanId, loan));

        for (FolderTemplate t : templates.findByActiveTrueOrderBySortOrderAsc()) {
            String normalized = normalize(t.getDisplayName());
            boolean exists = folders
                    .findByLoanIdAndParentIdAndNameNormalizedAndDeletedAtIsNull(loanId, root.getId(), normalized)
                    .isPresent();
            if (exists) continue; // collision → skip silently (idempotent)

            Folder f = new Folder();
            f.setLoanId(loanId);
            f.setParentId(root.getId());
            f.setDisplayName(t.getDisplayName());
            f.setNameNormalized(normalized);
            f.setSortKey(t.getSortKey());
            f.setSystem(true);
            f.setOldLoanArchive(t.isOldLoanArchive());
            f.setDeleteFolder(t.isDeleteFolder());
            f.setFolderTemplateId(t.getId());
            folders.save(f);
        }
        return root;
    }

    private Folder createRoot(UUID loanId, Loan loan) {
        String name = rootName(loanId, loan);
        Folder root = new Folder();
        root.setLoanId(loanId);
        root.setParentId(null);
        root.setDisplayName(name);
        root.setNameNormalized(normalize(name));
        root.setSortKey("00"); // sorts ahead of templates "01".."17"; ordering also forces root first
        root.setSystem(true);
        return folders.save(root);
    }

    /**
     * Root display name: the primary borrower's name if resolvable, else "Loan {loanNumber}",
     * else "Loan {id}".
     */
    private String rootName(UUID loanId, Loan loan) {
        String borrower = borrowerNameResolver
                .primaryBorrowerNamesByLoanIds(List.of(loanId))
                .get(loanId);
        if (borrower != null && !borrower.isBlank()) return borrower.trim();
        if (loan.getLoanNumber() != null && !loan.getLoanNumber().isBlank()) {
            return "Loan " + loan.getLoanNumber();
        }
        return "Loan " + loanId;
    }

    // ── writes ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public Folder create(UUID loanId, UUID parentId, String displayName) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);
        String name = requireName(displayName);

        ensureSeeded(loanId, loan);

        // Resolve the parent: explicit parent must be live + same loan; null → the loan's root.
        Folder parent = (parentId != null)
                ? load(loanId, parentId)
                : folders.findByLoanIdAndParentIdIsNullAndDeletedAtIsNull(loanId)
                        .orElseThrow(() -> new NotFoundException("Folder root", loanId));

        String normalized = normalize(name);
        folders.findByLoanIdAndParentIdAndNameNormalizedAndDeletedAtIsNull(loanId, parent.getId(), normalized)
                .ifPresent(x -> { throw collision(name); });

        Folder f = new Folder();
        f.setLoanId(loanId);
        f.setParentId(parent.getId());
        f.setDisplayName(name);
        f.setNameNormalized(normalized);
        f.setSystem(false);
        return folders.save(f);
    }

    @Transactional
    public Folder rename(UUID loanId, UUID folderId, String displayName) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        String name = requireName(displayName);
        Folder f = load(loanId, folderId);

        String normalized = normalize(name);
        // System folders CAN be renamed. Skip the collision check on a case-only no-op
        // (same normalized name) — otherwise a sibling lookup would flag the folder against itself.
        if (!normalized.equals(f.getNameNormalized())) {
            folders.findByLoanIdAndParentIdAndNameNormalizedAndDeletedAtIsNull(loanId, f.getParentId(), normalized)
                    .filter(sib -> !sib.getId().equals(f.getId()))
                    .ifPresent(sib -> { throw collision(name); });
        }
        f.setDisplayName(name);
        f.setNameNormalized(normalized);
        return folders.save(f);
    }

    @Transactional
    public void softDelete(UUID loanId, UUID folderId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Folder f = load(loanId, folderId);
        if (f.isSystem()) {
            throw new ValidationException("Default folders cannot be deleted. Rename instead.");
        }
        f.setDeletedAt(java.time.Instant.now());
        folders.save(f);
        // Documents inside keep their folder_id → they simply become unfiled in the tree view.
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private Folder load(UUID loanId, UUID folderId) {
        return folders.findByIdAndOrgId(folderId, tenantContext.requireOrgId())
                .filter(x -> x.getDeletedAt() == null)
                .filter(x -> x.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Folder", folderId));
    }

    private static String requireName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new ValidationException("displayName is required");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > 255) {
            throw new ValidationException("displayName must be <= 255 characters");
        }
        return trimmed;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private static ValidationException collision(String name) {
        return new ValidationException("A folder named \"" + name + "\" already exists here");
    }
}
