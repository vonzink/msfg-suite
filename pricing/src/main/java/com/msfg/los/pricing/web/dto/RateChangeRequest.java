package com.msfg.los.pricing.web.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record RateChangeRequest(
        @NotNull @DecimalMin("0.125") @DecimalMax("25.000") BigDecimal rate) {}
