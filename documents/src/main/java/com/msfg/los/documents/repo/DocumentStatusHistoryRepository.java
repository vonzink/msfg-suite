package com.msfg.los.documents.repo;

import com.msfg.los.documents.domain.DocumentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only document status history. Tenant-filtered via {@code @TenantId}.
 */
public interface DocumentStatusHistoryRepository extends JpaRepository<DocumentStatusHistory, UUID> {

    List<DocumentStatusHistory> findByDocumentIdOrderByTransitionedAtAsc(UUID documentId);
}
