package com.msfg.los.platform.pii;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PiiAccessRecorder {

    private final PiiAccessLogRepository repo;

    public PiiAccessRecorder(PiiAccessLogRepository repo) {
        this.repo = repo;
    }

    /** Records one NPI access. createdBy/createdAt (who/when) set by JPA auditing; org_id by @TenantId. */
    @Transactional
    public void record(String subjectType, UUID subjectId, String field, String reason) {
        PiiAccessLog log = new PiiAccessLog();
        log.setSubjectType(subjectType);
        log.setSubjectId(subjectId);
        log.setField(field);
        log.setReason(reason);
        repo.save(log);
    }
}
