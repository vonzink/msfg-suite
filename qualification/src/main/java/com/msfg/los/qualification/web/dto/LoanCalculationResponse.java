package com.msfg.los.qualification.web.dto;

import java.math.BigDecimal;

/**
 * All derived qualification figures + the echo of inputs used.
 * Null fields mean the figure is not computable from current inputs — never a 500.
 */
public record LoanCalculationResponse(

        // ── Echoed inputs (transparency) ──────────────────────────────────────
        BigDecimal baseLoanAmount,
        BigDecimal secondLoanAmount,
        BigDecimal financedFeesAmount,
        BigDecimal interestRate,
        Integer    loanTermMonths,
        String     loanPurpose,

        // ── Derived loan amounts ───────────────────────────────────────────────
        BigDecimal totalLoanAmount,

        // ── LTV basis + ratios ─────────────────────────────────────────────────
        BigDecimal ltvBasis,
        BigDecimal ltv,
        BigDecimal cltv,
        BigDecimal tltv,

        // ── Monthly P&I and proposed housing (PITI) ───────────────────────────
        BigDecimal monthlyPrincipalInterest,
        BigDecimal proposedHousingExpense,

        // ── Present housing comparison (best-effort, may be null) ─────────────
        BigDecimal presentHousingExpense,
        BigDecimal housingExpenseDelta,

        // ── Income side ───────────────────────────────────────────────────────
        BigDecimal baseMonthlyIncome,
        BigDecimal netRentalIncome,
        BigDecimal totalMonthlyIncome,

        // ── Debt side ─────────────────────────────────────────────────────────
        BigDecimal dtiLiabilityPayments,
        BigDecimal netRentalDebt,
        BigDecimal totalMonthlyDebts,

        // ── DTI ratios ────────────────────────────────────────────────────────
        BigDecimal frontEndDti,
        BigDecimal backEndDti
) {}
