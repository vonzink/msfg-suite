package com.msfg.los.financials.web.dto;

import com.msfg.los.financials.domain.AssetVerificationType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record OrderAssetVerificationRequest(
        @NotNull AssetVerificationType verificationType,
        UUID borrowerId
) {}
