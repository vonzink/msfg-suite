package com.msfg.los.identity.service;

import com.msfg.los.identity.domain.VerificationRequest;
import com.msfg.los.identity.repo.VerificationRequestRepository;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists a failed verification attempt in its OWN transaction so the increment SURVIVES the outer
 * verify transaction's rollback (the verify path throws a generic failure, which rolls back the caller's
 * tx — a plain in-tx increment would be lost, defeating the lockout). Mirrors the
 * {@code AusRunErrorRecorder} REQUIRES_NEW pattern (the dead-ERROR-row lesson from the AUS spec).
 *
 * <p>Loads the row by {@code findByIdAndOrgId} (NOT {@code findById}): {@code @TenantId} does not filter
 * find()-by-PK (CLAUDE.md), and the bound tenant is still on the thread in this REQUIRES_NEW tx, so the
 * lookup stays fail-closed to the caller's tenant.
 */
@Service
public class VerificationAttemptRecorder {

    private final VerificationRequestRepository requests;
    private final TenantContext tenantContext;

    public VerificationAttemptRecorder(VerificationRequestRepository requests, TenantContext tenantContext) {
        this.requests = requests;
        this.tenantContext = tenantContext;
    }

    /** Increment the attempts counter for the given row in a committed, independent transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID requestId) {
        requests.findByIdAndOrgId(requestId, tenantContext.requireOrgId()).ifPresent((VerificationRequest r) ->
                r.setAttempts(r.getAttempts() + 1));
    }
}
