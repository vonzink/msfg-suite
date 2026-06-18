package com.msfg.los.documents.repo;

import com.msfg.los.documents.domain.DocumentTypeCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Org-scoped document-type catalog repository. Tenant-filtered via {@code @TenantId};
 * loads-by-PK use {@code findByIdAndOrgId}.
 */
public interface DocumentTypeCatalogRepository extends JpaRepository<DocumentTypeCatalog, UUID> {

    Optional<DocumentTypeCatalog> findBySlug(String slug);

    List<DocumentTypeCatalog> findByActiveTrueOrderBySortOrderAsc();

    List<DocumentTypeCatalog> findAllByOrderBySortOrderAsc();

    boolean existsBySlug(String slug);

    Optional<DocumentTypeCatalog> findByIdAndOrgId(UUID id, UUID orgId);
}
