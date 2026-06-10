package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanStatus;
import com.msfg.los.loan.domain.SubjectProperty;
import java.time.Instant;
import java.util.UUID;

public record LoanListItemResponse(
        UUID id,
        String loanNumber,
        LoanStatus status,
        UUID loanOfficerId,
        String primaryBorrowerName,
        String propertyCity,
        String propertyState,
        Instant updatedAt) {

    public static LoanListItemResponse from(Loan l, String primaryBorrowerName) {
        SubjectProperty sp = l.getSubjectProperty();
        return new LoanListItemResponse(
            l.getId(),
            l.getLoanNumber(),
            l.getStatus(),
            l.getLoanOfficerId(),
            primaryBorrowerName,
            sp != null ? sp.getCity() : null,
            sp != null ? sp.getState() : null,
            l.getUpdatedAt());
    }
}
