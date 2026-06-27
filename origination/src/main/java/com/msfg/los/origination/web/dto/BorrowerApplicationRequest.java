package com.msfg.los.origination.web.dto;

import com.msfg.los.loan.domain.MortgageType;
import com.msfg.los.loan.domain.OccupancyType;
import com.msfg.los.loan.domain.PropertyType;
import com.msfg.los.parties.domain.CitizenshipType;
import com.msfg.los.parties.domain.MaritalStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Borrower-self application upsert (Stage 2) — the shape the client {@code /apply} form submits.
 *
 * <p>Deliberately contains ONLY borrower-writable fields: the §4 loan SUBSET a borrower may set
 * (address, value/price, loan + down-payment amount, property type/occupancy/units, mortgage type)
 * and the caller's own borrower personal info. It has NO {@code interestRate}/{@code loanTermMonths}/
 * pricing/UW fields — those stay staff-only and can never reach {@code LoanService.update} through this
 * path (the orchestrator also hard-nulls them when mapping, as defence in depth).
 *
 * <p>Merge semantics: any {@code null} field is left unchanged (matches {@code LoanService.update} /
 * {@code BorrowerService.updateInternal}), so a partial save never wipes an LO's concurrent edits.
 */
public record BorrowerApplicationRequest(LoanInfo loan, BorrowerInfo borrower) {

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
        String ssn,
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
