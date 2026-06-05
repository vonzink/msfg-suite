package com.msfg.los.income.web.dto;

import com.msfg.los.income.domain.IncomeType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AddIncomeRequest(
        @NotNull IncomeType incomeType,
        @NotNull BigDecimal monthlyAmount,
        UUID employmentId,
        String description) {}
