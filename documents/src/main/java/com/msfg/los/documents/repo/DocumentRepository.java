package com.msfg.los.documents.repo;

import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findByLoanIdOrderByCreatedAtDesc(UUID loanId, Pageable pageable);

    Page<Document> findByLoanIdAndDocumentTypeOrderByCreatedAtDesc(UUID loanId, DocumentType documentType, Pageable pageable);

    Optional<Document> findByIdAndOrgId(UUID id, UUID orgId);
}
