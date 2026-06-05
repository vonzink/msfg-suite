package com.msfg.los.income.verification;

import com.msfg.los.income.domain.VerificationStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class StubIncomeVerificationAdapter implements IncomeVerificationPort {

    @Override
    public IncomeVerificationResult order(OrderIncomeVerificationCommand c) {
        // No real vendor yet — record an immediate ORDERED with a synthetic reference.
        return new IncomeVerificationResult(VerificationStatus.ORDERED, "STUB",
                "STUB-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
