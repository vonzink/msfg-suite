package com.msfg.los.income.verification;

public interface IncomeVerificationPort {
    IncomeVerificationResult order(OrderIncomeVerificationCommand command);
}
