package com.msfg.los.documents.repo;

import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link JpaSpecificationExecutor} is mixed in so the list/faceted-search endpoints push every
 * filter (status, type, folder, partyRole, uploadedBy, fileName-contains) INTO the SQL query
 * (see {@code DocumentSpecifications}) — never load-all-then-filter in memory.
 */
public interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {

    Page<Document> findByLoanIdOrderByCreatedAtDesc(UUID loanId, Pageable pageable);

    Page<Document> findByLoanIdAndDocumentTypeOrderByCreatedAtDesc(UUID loanId, DocumentType documentType, Pageable pageable);

    Optional<Document> findByIdAndOrgId(UUID id, UUID orgId);

    // ── Phase 1 (cutover): soft-delete-aware + loan/folder-scoped finders ───────────

    /** Load a single live (not soft-deleted) document scoped to its loan. */
    Optional<Document> findByIdAndLoanIdAndDeletedAtIsNull(UUID id, UUID loanId);

    /** Load by id within a loan regardless of soft-delete (e.g. permanent-delete path). */
    Optional<Document> findByIdAndLoanId(UUID id, UUID loanId);

    /** Live documents for a loan. */
    Page<Document> findByLoanIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID loanId, Pageable pageable);

    /** Live documents in a specific folder. */
    Page<Document> findByLoanIdAndFolderIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID loanId, UUID folderId, Pageable pageable);

    /** Live unfiled documents (folderId null). */
    Page<Document> findByLoanIdAndFolderIdIsNullAndDeletedAtIsNullOrderByCreatedAtDesc(UUID loanId, Pageable pageable);

    /** Live documents referencing a folder — used to block/relocate on folder delete. */
    List<Document> findByLoanIdAndFolderIdAndDeletedAtIsNull(UUID loanId, UUID folderId);
}
