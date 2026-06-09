package com.msfg.los.financials.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record AssetSummaryResponse(List<AssetSummaryRow> rows, BigDecimal totalAssets) {}
