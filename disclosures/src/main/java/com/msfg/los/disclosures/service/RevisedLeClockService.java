package com.msfg.los.disclosures.service;

import com.msfg.los.coc.domain.CocHistoryEntry;
import com.msfg.los.coc.domain.CocStatus;
import com.msfg.los.coc.service.CocService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Computes the revised-LE delivery deadline driven by a valid changed circumstance.
 *
 * <p>Under 12 CFR 1026.19(e)(3)(iv)/(e)(4), a changed circumstance lets the creditor re-establish
 * good faith only if it delivers a revised Loan Estimate no later than 3 <em>general</em> business
 * days after receiving information sufficient to establish the change. In this system an ACCEPTED
 * Change-of-Circumstance ({@link CocStatus#ACCEPTED}) is the recognition event; its decision date
 * starts the 3-business-day clock. The {@link DisclosureTimingService} owns the business-day math.
 */
@Service
public class RevisedLeClockService {

    private final CocService cocService;
    private final DisclosureTimingService timingService;

    public RevisedLeClockService(CocService cocService,
                                 DisclosureTimingService timingService) {
        this.cocService = cocService;
        this.timingService = timingService;
    }

    /**
     * The revised-LE delivery deadline for a loan, or {@code null} if no CoC has been accepted. Uses
     * the most recent accepted CoC by decision date; the deadline is 3 general business days after
     * that decision date.
     */
    public LocalDate revisedLeDeadline(UUID loanId) {
        return cocService.latestByStatus(loanId, CocStatus.ACCEPTED)
                .map(CocHistoryEntry::getDecisionDate)
                .filter(java.util.Objects::nonNull)
                .map(instant -> timingService.revisedLeDeadline(instant.atZone(ZoneOffset.UTC).toLocalDate()))
                .orElse(null);
    }
}
