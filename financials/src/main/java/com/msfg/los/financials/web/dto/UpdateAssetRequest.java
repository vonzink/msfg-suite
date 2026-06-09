package com.msfg.los.financials.web.dto;

import com.msfg.los.financials.domain.AssetType;

import java.math.BigDecimal;

public record UpdateAssetRequest(
        AssetType assetType,
        String financialInstitution,
        String accountNumber,
        BigDecimal cashOrMarketValue,
        Boolean verified) {}
