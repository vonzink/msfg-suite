package com.msfg.los.income.web.dto;

import com.msfg.los.income.domain.IncomeType;

import java.math.BigDecimal;
import java.util.UUID;

public record UpdateIncomeRequest(
        IncomeType incomeType,
        BigDecimal monthlyAmount,
        UUID employmentId,
        String description) {}
