package com.msfg.los.financials.web.dto;

import com.msfg.los.financials.domain.Asset;
import com.msfg.los.financials.domain.AssetType;

import java.math.BigDecimal;
import java.util.UUID;

public record AssetResponse(
        UUID id,
        UUID borrowerId,
        AssetType assetType,
        int ordinal,
        String financialInstitution,
        String accountNumber,
        BigDecimal cashOrMarketValue,
        Boolean verified) {

    public static AssetResponse from(Asset asset) {
        return new AssetResponse(
                asset.getId(),
                asset.getBorrowerId(),
                asset.getAssetType(),
                asset.getOrdinal(),
                asset.getFinancialInstitution(),
                asset.getAccountNumber(),
                asset.getCashOrMarketValue(),
                asset.getVerified());
    }
}
