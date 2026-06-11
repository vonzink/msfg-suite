package com.msfg.los.fees.web.dto;

import java.math.BigDecimal;
import java.util.Map;

public record FeeTotalsResponse(Map<String, BigDecimal> sectionTotals, CategoryTotals categoryTotals) {

    public record CategoryTotals(
            BigDecimal origination,
            BigDecimal didNotShop,
            BigDecimal didShop,
            BigDecimal taxesGov,
            BigDecimal escrowPrepaids) {}
}
