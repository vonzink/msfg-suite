package com.msfg.los.reo.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReoSummaryResponse(
        List<ReoSummaryRow> rows,
        BigDecimal totalMarketValue,
        BigDecimal totalGrossMonthlyRentalIncome,
        BigDecimal totalMonthlyExpenses,
        BigDecimal totalMortgageUnpaidBalance,
        BigDecimal totalMonthlyMortgagePayment
) {}
