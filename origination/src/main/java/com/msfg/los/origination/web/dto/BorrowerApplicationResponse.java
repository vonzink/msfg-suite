package com.msfg.los.origination.web.dto;

import com.msfg.los.loan.domain.MortgageType;
import com.msfg.los.loan.domain.OccupancyType;
import com.msfg.los.loan.domain.PropertyType;
import com.msfg.los.parties.domain.CitizenshipType;
import com.msfg.los.parties.domain.MaritalStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Hydration view for the borrower-self application (Stage 2) — the current suite state of the loan's
 * §4 subset + the caller's own borrower row, used to populate the {@code /apply} form (so the borrower
 * AND the LO see each other's edits). NPI-safe: the borrower's SSN is NEVER returned, only
 * {@code hasSsn} (a boolean "on file" indicator) — full SSN stays behind the audited reveal endpoint.
 */
public record BorrowerApplicationResponse(
    UUID loanId,
    String loanNumber,
    UUID borrowerId,
    LoanInfo loan,
    BorrowerInfo borrower) {

    public record LoanInfo(
        MortgageType mortgageType,
        BigDecimal baseLoanAmount,
        BigDecimal downPaymentAmount,
        BigDecimal estimatedValue,
        BigDecimal salesPrice,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        PropertyType propertyType,
        OccupancyType occupancyType,
        Integer numberOfUnits) {}

    public record BorrowerInfo(
        String firstName,
        String lastName,
        String middleName,
        String suffix,
        boolean hasSsn,
        LocalDate dateOfBirth,
        MaritalStatus maritalStatus,
        Integer dependentsCount,
        String dependentAges,
        CitizenshipType citizenshipType,
        String homePhone,
        String cellPhone,
        String workPhone,
        String workPhoneExt,
        String email) {}
}
