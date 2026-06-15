package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.domain.DisclosureIssuance;
import com.msfg.los.disclosures.domain.DisclosureKind;
import com.msfg.los.disclosures.domain.DisclosureSnapshot;
import com.msfg.los.disclosures.domain.DisclosureStatus;
import com.msfg.los.disclosures.repo.DisclosureIssuanceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persists the ERROR audit row for a failed disclosure vendor call (generate or send).
 *
 * <p>REQUIRES_NEW so the ERROR row survives the outer transaction's rollback when a vendor call
 * fails: {@link DisclosureIssuanceService#issue} rethrows after recording, which marks the outer
 * transaction rollback-only — a same-transaction save would silently vanish with it.
 */
@Component
public class DisclosureIssuanceErrorRecorder {

    private static final int MAX_ERROR_MESSAGE = 1000;

    private final DisclosureIssuanceRepository issuanceRepo;

    public DisclosureIssuanceErrorRecorder(DisclosureIssuanceRepository issuanceRepo) {
        this.issuanceRepo = issuanceRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DisclosureIssuance recordError(UUID loanId, DisclosureKind kind, String requestedBy, String message) {
        DisclosureIssuance error = new DisclosureIssuance();
        error.setLoanId(loanId);
        error.setKind(kind);
        error.setStatus(DisclosureStatus.ERROR);
        error.setErrorMessage(truncate(message));
        error.setDisclosureVersion(nextVersion(loanId, kind));
        // snapshot is NOT NULL — the vendor call failed before any figures were assembled into one,
        // so persist an empty snapshot to satisfy the constraint without inventing data.
        error.setSnapshot(new DisclosureSnapshot(List.of(), List.of(), null, null, null));
        error.setRequestedBy(requestedBy);
        error.setRequestedAt(Instant.now());
        return issuanceRepo.save(error);
    }

    private int nextVersion(UUID loanId, DisclosureKind kind) {
        return issuanceRepo.findTopByLoanIdAndKindOrderByDisclosureVersionDesc(loanId, kind)
                .map(prev -> prev.getDisclosureVersion() + 1)
                .orElse(1);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_MESSAGE ? message : message.substring(0, MAX_ERROR_MESSAGE);
    }
}
