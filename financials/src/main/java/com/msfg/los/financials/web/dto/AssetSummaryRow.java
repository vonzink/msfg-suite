package com.msfg.los.financials.web.dto;

import com.msfg.los.financials.domain.AssetType;
import java.math.BigDecimal;
import java.util.UUID;

public record AssetSummaryRow(UUID borrowerId, String borrowerName, AssetType assetType,
                              String financialInstitution, BigDecimal cashOrMarketValue) {}
