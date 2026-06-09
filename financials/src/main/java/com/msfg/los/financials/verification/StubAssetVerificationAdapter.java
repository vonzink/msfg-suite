package com.msfg.los.financials.verification;

import com.msfg.los.financials.domain.AssetVerificationStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class StubAssetVerificationAdapter implements AssetVerificationPort {

    @Override
    public AssetVerificationResult order(OrderAssetVerificationCommand c) {
        // No real vendor yet — record an immediate ORDERED with a synthetic reference.
        return new AssetVerificationResult(AssetVerificationStatus.ORDERED, "STUB",
                "STUB-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
