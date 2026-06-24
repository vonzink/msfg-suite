package com.msfg.los.identity.service;

import com.msfg.los.identity.domain.VerificationRequest;
import com.msfg.los.identity.repo.VerificationRequestRepository;
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
 * <p>Loads the row by id under {@code @TenantId} so the separate tx still sees only the caller's tenant.
 */
@Service
public class VerificationAttemptRecorder {

    private final VerificationRequestRepository requests;

    public VerificationAttemptRecorder(VerificationRequestRepository requests) {
        this.requests = requests;
    }

    /** Increment the attempts counter for the given row in a committed, independent transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID requestId) {
        requests.findById(requestId).ifPresent((VerificationRequest r) ->
                r.setAttempts(r.getAttempts() + 1));
    }
}
