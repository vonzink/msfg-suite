package com.msfg.los.documents.repo;

import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentStatus;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/**
 * JPA {@link Specification}s for the document list/search endpoints. Every optional filter is
 * applied as a SQL predicate so filtering happens IN THE QUERY (never load-all-then-filter).
 *
 * <p>Tenant scoping is NOT expressed here — Hibernate {@code @TenantId} already auto-filters every
 * query by the caller's {@code org_id}; these specs only add loan + business filters.
 */
public final class DocumentSpecifications {

    private DocumentSpecifications() {
    }

    /** Loan-scoped + not soft-deleted: the always-on base of every list/search query. */
    public static Specification<Document> inLoanNotDeleted(UUID loanId) {
        return (root, q, cb) -> cb.and(
                cb.equal(root.get("loanId"), loanId),
                cb.isNull(root.get("deletedAt")));
    }

    /** Confirmed working set: status is neither PENDING_UPLOAD nor DELETED_SOFT (the {@code GET /} list). */
    public static Specification<Document> confirmedOnly() {
        return (root, q, cb) -> root.get("documentStatus")
                .in(DocumentStatus.PENDING_UPLOAD, DocumentStatus.DELETED_SOFT).not();
    }

    public static Specification<Document> hasStatus(DocumentStatus status) {
        return (root, q, cb) -> cb.equal(root.get("documentStatus"), status);
    }

    public static Specification<Document> hasDocumentTypeId(UUID documentTypeId) {
        return (root, q, cb) -> cb.equal(root.get("documentTypeId"), documentTypeId);
    }

    /** Exact folder match. A null {@code folderId} matches unfiled documents (folder_id is null). */
    public static Specification<Document> inFolder(UUID folderId) {
        return (root, q, cb) -> folderId == null
                ? cb.isNull(root.get("folderId"))
                : cb.equal(root.get("folderId"), folderId);
    }

    public static Specification<Document> uploadedBy(String createdBy) {
        return (root, q, cb) -> cb.equal(root.get("createdBy"), createdBy);
    }

    public static Specification<Document> hasPartyRole(String partyRole) {
        return (root, q, cb) -> cb.equal(root.get("partyRole"), partyRole);
    }

    /** Case-insensitive fileName-contains. */
    public static Specification<Document> fileNameContains(String q) {
        String like = "%" + q.toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("fileName")), like);
    }

    /**
     * Compose the search predicate: loan + not-deleted base AND every supplied (non-null/non-blank)
     * facet. Used by {@code GET /search}.
     */
    public static Specification<Document> search(UUID loanId,
                                                 DocumentStatus status,
                                                 UUID documentTypeId,
                                                 UUID folderId,
                                                 boolean folderIdProvided,
                                                 String uploadedBy,
                                                 String partyRole,
                                                 String q) {
        Specification<Document> spec = inLoanNotDeleted(loanId);
        if (status != null) spec = spec.and(hasStatus(status));
        if (documentTypeId != null) spec = spec.and(hasDocumentTypeId(documentTypeId));
        if (folderIdProvided) spec = spec.and(inFolder(folderId));
        if (uploadedBy != null && !uploadedBy.isBlank()) spec = spec.and(uploadedBy(uploadedBy.trim()));
        if (partyRole != null && !partyRole.isBlank()) spec = spec.and(hasPartyRole(partyRole.trim()));
        if (q != null && !q.isBlank()) spec = spec.and(fileNameContains(q.trim()));
        return spec;
    }
}
