package com.msfg.los.pricing.web.dto;

import com.msfg.los.pricing.domain.PricingAdjustment;
import com.msfg.los.pricing.domain.PricingRowType;
import java.math.BigDecimal;

public record PricingAdjustmentResponse(
        int ordinal, String name, PricingRowType rowType,
        BigDecimal adjustmentPercent, BigDecimal dollarAmount) {

    public static PricingAdjustmentResponse from(PricingAdjustment a) {
        return new PricingAdjustmentResponse(a.getOrdinal(), a.getName(), a.getRowType(),
                a.getAdjustmentPercent(), a.getDollarAmount());
    }
}
