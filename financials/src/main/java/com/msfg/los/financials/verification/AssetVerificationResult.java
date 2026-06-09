package com.msfg.los.financials.verification;

import com.msfg.los.financials.domain.VerificationStatus;

public record AssetVerificationResult(VerificationStatus status, String provider, String referenceNumber) {}
