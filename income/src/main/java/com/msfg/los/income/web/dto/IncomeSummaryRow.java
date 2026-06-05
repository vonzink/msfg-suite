package com.msfg.los.income.web.dto;

import com.msfg.los.income.domain.IncomeType;
import java.math.BigDecimal;
import java.util.UUID;

public record IncomeSummaryRow(UUID borrowerId, String borrowerName, IncomeType incomeType,
                               String employerName, BigDecimal monthlyAmount) {}
