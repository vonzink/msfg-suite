package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.*;
import java.math.BigDecimal;
import java.util.UUID;

public record LoanSummaryResponse(
    UUID id,
    String loanNumber,
    LoanStatus status,
    LoanPurposeType loanPurpose,
    MortgageType mortgageType,
    BigDecimal noteAmount,
    UUID loanOfficerId,
    String propertyCity,
    String propertyState) {

    public static LoanSummaryResponse from(Loan l) {
        SubjectProperty sp = l.getSubjectProperty();
        return new LoanSummaryResponse(
            l.getId(), l.getLoanNumber(), l.getStatus(),
            l.getLoanPurpose(), l.getMortgageType(), l.getNoteAmount(), l.getLoanOfficerId(),
            sp != null ? sp.getCity() : null,
            sp != null ? sp.getState() : null);
    }
}
