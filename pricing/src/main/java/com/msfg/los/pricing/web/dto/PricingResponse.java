package com.msfg.los.pricing.web.dto;

import com.msfg.los.pricing.domain.CompensationPayerType;
import com.msfg.los.pricing.domain.RateLockStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The Products & Pricing grid. Lock fields are null when NOT_LOCKED. */
public record PricingResponse(
        RateLockStatus lockStatus,
        BigDecimal interestRate,
        Integer commitmentDays,
        Instant lockDate,
        LocalDate currentExpiration,
        Integer extensionDaysTotal,
        CompensationPayerType compensationPayerType,
        String lockedBy,
        String interviewerEmail,
        BigDecimal totalLoanAmount,
        String exactRateType) {}
