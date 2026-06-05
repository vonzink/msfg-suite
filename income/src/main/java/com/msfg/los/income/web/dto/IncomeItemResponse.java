package com.msfg.los.income.web.dto;

import com.msfg.los.income.domain.IncomeItem;
import com.msfg.los.income.domain.IncomeType;

import java.math.BigDecimal;
import java.util.UUID;

public record IncomeItemResponse(
        UUID id,
        UUID borrowerId,
        UUID employmentId,
        IncomeType incomeType,
        BigDecimal monthlyAmount,
        String description,
        int ordinal) {

    public static IncomeItemResponse from(IncomeItem item) {
        return new IncomeItemResponse(
                item.getId(),
                item.getBorrowerId(),
                item.getEmploymentId(),
                item.getIncomeType(),
                item.getMonthlyAmount(),
                item.getDescription(),
                item.getOrdinal());
    }
}
