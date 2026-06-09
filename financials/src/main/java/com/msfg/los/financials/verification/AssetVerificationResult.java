package com.msfg.los.financials.verification;

import com.msfg.los.financials.domain.AssetVerificationStatus;

public record AssetVerificationResult(AssetVerificationStatus status, String provider, String referenceNumber) {}
