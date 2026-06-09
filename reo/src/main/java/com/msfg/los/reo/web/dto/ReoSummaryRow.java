package com.msfg.los.reo.web.dto;

import com.msfg.los.loan.domain.PropertyType;
import com.msfg.los.reo.domain.ReoPropertyStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record ReoSummaryRow(
        UUID reoId,
        boolean isSubjectProperty,
        PropertyType propertyType,
        ReoPropertyStatus propertyStatus,
        BigDecimal marketValue,
        BigDecimal grossMonthlyRentalIncome
) {}
