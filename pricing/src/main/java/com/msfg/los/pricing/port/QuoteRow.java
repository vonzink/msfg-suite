package com.msfg.los.pricing.port;

import com.msfg.los.pricing.domain.PricingRowType;
import java.math.BigDecimal;

public record QuoteRow(String name, PricingRowType rowType, BigDecimal percent) {}
