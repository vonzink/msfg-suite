package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.*;
import java.math.BigDecimal;

public record UpdateLoanRequest(
    MortgageType mortgageType,
    LienPriorityType lienPriority,
    AmortizationType amortizationType,
    BigDecimal noteAmount,
    String addressLine1,
    String addressLine2,
    String city,
    String state,
    String postalCode,
    BigDecimal estimatedValue,
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
    Integer numberOfUnits) {}
