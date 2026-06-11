package com.msfg.los.pricing.web.dto;

import com.msfg.los.pricing.domain.CompensationPayerType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Body for control-your-price AND relock. */
public record LockTermsRequest(
        @NotNull @DecimalMin("0.125") @DecimalMax("25.000") BigDecimal rate,
        @NotNull @AllowedCommitmentDays Integer commitmentDays,
        @NotNull CompensationPayerType compensationPayerType) {}
