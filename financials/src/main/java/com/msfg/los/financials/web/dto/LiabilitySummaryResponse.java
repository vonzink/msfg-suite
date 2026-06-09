package com.msfg.los.financials.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record LiabilitySummaryResponse(List<LiabilitySummaryRow> rows, BigDecimal totalMonthlyPayments,
                                       BigDecimal dtiMonthlyPayments, BigDecimal totalUnpaidBalance) {}
