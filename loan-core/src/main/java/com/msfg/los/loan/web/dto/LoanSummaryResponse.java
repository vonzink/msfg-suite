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
    String propertyState,
    // §4 Loan Information fields
    DocumentationType documentationType,
    BigDecimal interestRate,
    Integer loanTermMonths,
    BigDecimal baseLoanAmount,
    BigDecimal financedFeesAmount,
    BigDecimal secondLoanAmount,
    BigDecimal downPaymentAmount,
    Integer qualifyingCreditScore,
    BigDecimal proposedTaxesMonthly,
    BigDecimal proposedHazardInsuranceMonthly,
    BigDecimal proposedHoaDuesMonthly,
    BigDecimal proposedMortgageInsuranceMonthly,
    // §4 Subject Property fields
    BigDecimal salesPrice,
    BigDecimal appraisedValue,
    PropertyType propertyType,
    OccupancyType occupancyType,
    Integer numberOfUnits) {

    public static LoanSummaryResponse from(Loan l) {
        SubjectProperty sp = l.getSubjectProperty();
        return new LoanSummaryResponse(
            l.getId(), l.getLoanNumber(), l.getStatus(),
            l.getLoanPurpose(), l.getMortgageType(), l.getNoteAmount(), l.getLoanOfficerId(),
            sp != null ? sp.getCity() : null,
            sp != null ? sp.getState() : null,
            l.getDocumentationType(),
            l.getInterestRate(),
            l.getLoanTermMonths(),
            l.getBaseLoanAmount(),
            l.getFinancedFeesAmount(),
            l.getSecondLoanAmount(),
            l.getDownPaymentAmount(),
            l.getQualifyingCreditScore(),
            l.getProposedTaxesMonthly(),
            l.getProposedHazardInsuranceMonthly(),
            l.getProposedHoaDuesMonthly(),
            l.getProposedMortgageInsuranceMonthly(),
            sp != null ? sp.getSalesPrice() : null,
            sp != null ? sp.getAppraisedValue() : null,
            sp != null ? sp.getPropertyType() : null,
            sp != null ? sp.getOccupancyType() : null,
            sp != null ? sp.getNumberOfUnits() : null);
    }
}
