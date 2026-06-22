package com.msfg.los.origination.web.dto;

import com.msfg.los.loan.domain.LoanPurposeType;
import com.msfg.los.loan.domain.MortgageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Borrower funnel intake (msfg.us -> mortgage-app -> suite). Minimal 1003; nullable tolerates partial. */
public record IntakeRequest(
        @NotBlank String sourceLeadId,
        @NotNull LoanPurposeType loanPurpose,
        MortgageType mortgageType,
        Borrower borrower,
        Property property) {

    public record Borrower(String firstName, String lastName, String email, String phone) {}
    public record Property(String addressLine1, String city, String state, String postalCode,
                           BigDecimal estimatedValue) {}
}
