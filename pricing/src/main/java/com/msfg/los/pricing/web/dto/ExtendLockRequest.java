package com.msfg.los.pricing.web.dto;

import jakarta.validation.constraints.*;

public record ExtendLockRequest(
        @NotNull @Min(1) @Max(60) Integer additionalDays) {}
