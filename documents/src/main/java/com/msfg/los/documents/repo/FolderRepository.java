package com.msfg.los.documents.repo;

import com.msfg.los.documents.domain.Folder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Folder repository. Tenant scoping is enforced by Hibernate {@code @TenantId} on JPQL reads;
 * loads-by-PK use {@code findByIdAndOrgId} to avoid the find()-by-PK cross-tenant leak.
 */
public interface FolderRepository extends JpaRepository<Folder, UUID> {

    /** Live folder tree for a loan, ordered for stable rendering. */
    List<Folder> findByLoanIdAndDeletedAtIsNullOrderBySortKeyAscDisplayNameAsc(UUID loanId);

    /** The live root folder for a loan (parentId null). */
    Optional<Folder> findByLoanIdAndParentIdIsNullAndDeletedAtIsNull(UUID loanId);

    /** Live children of a parent folder. */
    List<Folder> findByLoanIdAndParentIdAndDeletedAtIsNullOrderBySortKeyAscDisplayNameAsc(UUID loanId, UUID parentId);

    /** Case-insensitive sibling lookup within a parent (nameNormalized = lower(trim(name))). */
    Optional<Folder> findByLoanIdAndParentIdAndNameNormalizedAndDeletedAtIsNull(UUID loanId, UUID parentId, String nameNormalized);

    /** Sibling lookup at root (parentId null). */
    Optional<Folder> findByLoanIdAndParentIdIsNullAndNameNormalizedAndDeletedAtIsNull(UUID loanId, String nameNormalized);

    Optional<Folder> findByIdAndOrgId(UUID id, UUID orgId);

    boolean existsByLoanId(UUID loanId);

    /** A live folder in the loan by case-insensitive name — used for default-folder routing. */
    Optional<Folder> findFirstByLoanIdAndNameNormalizedAndDeletedAtIsNull(UUID loanId, String nameNormalized);

    /** The loan's live Delete folder (is_delete_folder = true) — gates permanent deletion. */
    Optional<Folder> findFirstByLoanIdAndDeleteFolderTrueAndDeletedAtIsNull(UUID loanId);
}
