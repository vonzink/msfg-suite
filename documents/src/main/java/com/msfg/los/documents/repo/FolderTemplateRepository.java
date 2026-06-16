package com.msfg.los.documents.repo;

import com.msfg.los.documents.domain.FolderTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Org-scoped folder-template repository. Tenant-filtered via {@code @TenantId};
 * loads-by-PK use {@code findByIdAndOrgId}. App-enforced singletons for the
 * delete / old-loan-archive templates are checked through the finders below.
 */
public interface FolderTemplateRepository extends JpaRepository<FolderTemplate, UUID> {

    List<FolderTemplate> findByActiveTrueOrderBySortOrderAsc();

    List<FolderTemplate> findAllByOrderBySortOrderAsc();

    Optional<FolderTemplate> findByDisplayName(String displayName);

    Optional<FolderTemplate> findByDeleteFolderTrue();

    Optional<FolderTemplate> findByOldLoanArchiveTrue();

    boolean existsByDisplayName(String displayName);

    Optional<FolderTemplate> findByIdAndOrgId(UUID id, UUID orgId);
}
