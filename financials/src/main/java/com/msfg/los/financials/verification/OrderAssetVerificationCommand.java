package com.msfg.los.financials.verification;

import com.msfg.los.financials.domain.AssetVerificationType;

import java.util.UUID;

public record OrderAssetVerificationCommand(UUID loanId, UUID borrowerId, AssetVerificationType verificationType) {}
