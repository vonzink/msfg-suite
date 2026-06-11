package com.msfg.los.fees.web.dto;

import com.msfg.los.fees.domain.FeeSection;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AddFeeRequest(
        @NotNull FeeSection section,
        @NotNull String label,
        BigDecimal amount,
        BigDecimal sellerConcession,
        BigDecimal percent) {}
