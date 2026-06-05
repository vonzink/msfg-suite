package com.msfg.los.income.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record IncomeSummaryResponse(List<IncomeSummaryRow> rows, BigDecimal totalMonthlyIncome) {}
