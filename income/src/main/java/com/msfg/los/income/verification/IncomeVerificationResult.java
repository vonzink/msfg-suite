package com.msfg.los.income.verification;

import com.msfg.los.income.domain.VerificationStatus;

public record IncomeVerificationResult(VerificationStatus status, String provider, String referenceNumber) {}
