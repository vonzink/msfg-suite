package com.msfg.los.documents.repo;

import com.msfg.los.documents.domain.DocumentContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentContentRepository extends JpaRepository<DocumentContent, UUID> {

    Optional<DocumentContent> findByStorageKey(String storageKey);

    Optional<DocumentContent> findByIdAndOrgId(UUID id, UUID orgId);
}
