package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.*;
import java.util.UUID;

public record LoanListItemResponse(UUID id, String loanNumber, LoanStatus status, UUID loanOfficerId) {
    public static LoanListItemResponse from(Loan l) {
        return new LoanListItemResponse(l.getId(), l.getLoanNumber(), l.getStatus(), l.getLoanOfficerId());
    }
}
