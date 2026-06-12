package com.msfg.los.aus.service;

import java.math.BigDecimal;

/** Snapshot of the loan-file fields the AUS submission is built from. */
public record AusLoanFile(String loanNumber, BigDecimal noteAmount, BigDecimal propertyValue,
        BigDecimal interestRate, Integer termMonths, int borrowerCount, String fhaCaseNumber) {}
