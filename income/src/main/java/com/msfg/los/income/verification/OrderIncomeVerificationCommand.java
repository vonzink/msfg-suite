package com.msfg.los.income.verification;

import com.msfg.los.income.domain.VerificationType;

import java.util.UUID;

public record OrderIncomeVerificationCommand(UUID loanId, UUID borrowerId, VerificationType verificationType) {}
