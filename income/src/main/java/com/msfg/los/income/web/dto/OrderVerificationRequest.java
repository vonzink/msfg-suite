package com.msfg.los.income.web.dto;

import com.msfg.los.income.domain.VerificationType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record OrderVerificationRequest(
        @NotNull VerificationType verificationType,
        UUID borrowerId
) {}
