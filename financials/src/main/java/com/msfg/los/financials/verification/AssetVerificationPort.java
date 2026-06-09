package com.msfg.los.financials.verification;

public interface AssetVerificationPort {
    AssetVerificationResult order(OrderAssetVerificationCommand command);
}
