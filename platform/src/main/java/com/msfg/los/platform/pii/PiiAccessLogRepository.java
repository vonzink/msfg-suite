package com.msfg.los.platform.pii;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PiiAccessLogRepository extends JpaRepository<PiiAccessLog, UUID> {

    List<PiiAccessLog> findBySubjectIdOrderByCreatedAtDesc(UUID subjectId);
}
