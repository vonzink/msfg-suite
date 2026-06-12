package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.AusRun;
import com.msfg.los.aus.domain.AusRunStatus;
import com.msfg.los.aus.domain.AusVendor;
import com.msfg.los.aus.repo.AusRunRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists the ERROR audit row for a failed AUS vendor submission.
 *
 * <p>REQUIRES_NEW so the audit row survives the outer transaction's rollback when a vendor
 * submit fails: {@link AusRunService} rethrows after recording, which marks the outer
 * transaction rollback-only — a same-transaction save would silently vanish with it.
 */
@Component
public class AusRunErrorRecorder {

    private static final int MAX_ERROR_MESSAGE = 1000;

    private final AusRunRepository runs;

    public AusRunErrorRecorder(AusRunRepository runs) {
        this.runs = runs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AusRun recordError(UUID loanId, AusVendor vendor, String errorMessage, String requestedBy) {
        AusRun error = new AusRun();
        error.setLoanId(loanId);
        error.setVendor(vendor);
        error.setStatus(AusRunStatus.ERROR);
        error.setErrorMessage(truncate(errorMessage));
        error.setRequestedBy(requestedBy);
        error.setRequestedAt(Instant.now());
        return runs.save(error);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR_MESSAGE ? s : s.substring(0, MAX_ERROR_MESSAGE);
    }
}
