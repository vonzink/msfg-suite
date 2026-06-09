package com.msfg.los.financials.web.dto;

import com.msfg.los.financials.domain.AssetType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AddAssetRequest(
        @NotNull AssetType assetType,
        String financialInstitution,
        String accountNumber,
        BigDecimal cashOrMarketValue,
        Boolean verified) {}
