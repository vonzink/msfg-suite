package com.msfg.los.documents.service;

import com.msfg.los.documents.domain.DocumentTypeCatalog;
import com.msfg.los.documents.repo.DocumentTypeCatalogRepository;
import com.msfg.los.documents.web.dto.UpsertDocumentTypeRequest;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Org-scoped document-type catalog. All reads/writes are tenant-filtered via Hibernate
 * {@code @TenantId} (stamps the caller's {@code org_id} on writes, auto-filters JPQL reads);
 * by-PK loads use {@code findByIdAndOrgId} so a foreign-org id resolves to 404, never a leak.
 *
 * <p>Slug is the per-org unique business key (DB {@code unique(org_id, slug)}; the app pre-check
 * yields a friendly 400, the DB constraint is the backstop). Deactivation is soft (sets
 * {@code is_active=false}) — rows are never hard-deleted, so historical documents keep a
 * resolvable type.
 */
@Service
public class DocumentTypeCatalogService {

    private final DocumentTypeCatalogRepository repo;
    private final TenantContext tenantContext;

    public DocumentTypeCatalogService(DocumentTypeCatalogRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    // ── reads ────────────────────────────────────────────────────────────────────────────

    /** Active types for the current org, ordered by sort_order ('other' last at 99). */
    @Transactional(readOnly = true)
    public List<DocumentTypeCatalog> listActive() {
        return repo.findByActiveTrueOrderBySortOrderAsc();
    }

    /** All types (active + inactive) for the current org, ordered by sort_order. */
    @Transactional(readOnly = true)
    public List<DocumentTypeCatalog> listAll() {
        return repo.findAllByOrderBySortOrderAsc();
    }

    /** Type by slug within the current org, 404 if missing. */
    @Transactional(readOnly = true)
    public DocumentTypeCatalog getBySlug(String slug) {
        return repo.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Document type", slug));
    }

    /** Type by id within the current org, 404 if missing or foreign-org. */
    @Transactional(readOnly = true)
    public DocumentTypeCatalog get(UUID id) {
        return load(id);
    }

    // ── admin writes ───────────────────────────────────────────────────────────────────────

    @Transactional
    public DocumentTypeCatalog create(UpsertDocumentTypeRequest req) {
        String slug = normalizeSlug(req.slug());
        if (repo.existsBySlug(slug)) {
            throw slugCollision(slug);
        }
        DocumentTypeCatalog c = new DocumentTypeCatalog();
        c.setSlug(slug);
        apply(c, req);
        return repo.save(c);
    }

    @Transactional
    public DocumentTypeCatalog update(UUID id, UpsertDocumentTypeRequest req) {
        DocumentTypeCatalog c = load(id);
        String slug = normalizeSlug(req.slug());
        if (!slug.equals(c.getSlug())) {
            repo.findBySlug(slug)
                    .filter(other -> !other.getId().equals(c.getId()))
                    .ifPresent(other -> { throw slugCollision(slug); });
            c.setSlug(slug);
        }
        apply(c, req);
        return repo.save(c);
    }

    /** Soft-delete: set is_active=false. Idempotent. */
    @Transactional
    public void deactivate(UUID id) {
        DocumentTypeCatalog c = load(id);
        c.setActive(false);
        repo.save(c);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private DocumentTypeCatalog load(UUID id) {
        return repo.findByIdAndOrgId(id, tenantContext.requireOrgId())
                .orElseThrow(() -> new NotFoundException("Document type", id));
    }

    private static void apply(DocumentTypeCatalog c, UpsertDocumentTypeRequest req) {
        c.setName(req.name().trim());
        c.setDefaultFolderName(blankToNull(req.defaultFolderName()));
        c.setRequiredForMilestones(blankToNull(req.requiredForMilestones()));
        c.setAllowedMimeTypes(blankToNull(req.allowedMimeTypes()));
        c.setMaxFileSizeBytes(req.maxFileSizeBytes());
        c.setActive(req.activeOrDefault());
        c.setSortOrder(req.sortOrderOrDefault());
    }

    private static String normalizeSlug(String slug) {
        return slug == null ? "" : slug.trim().toLowerCase();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static ValidationException slugCollision(String slug) {
        return new ValidationException("A document type with slug \"" + slug + "\" already exists");
    }
}
