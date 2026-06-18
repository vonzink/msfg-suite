package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.LoanStatus;
import java.util.UUID;

/**
 * Slim typeahead hit (Phase 2 T3) for {@code GET /api/loans/search}. Unique DTO name (not
 * LoanListItemResponse) so springdoc keys it distinctly.
 */
public record LoanSearchHit(
        UUID id,
        String loanNumber,
        String borrowerName,
        String propertyCity,
        String propertyState,
        LoanStatus status) {
}
