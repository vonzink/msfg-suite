package com.msfg.los.pricing.port;

import com.msfg.los.pricing.domain.PricingRowType;
import java.math.BigDecimal;

/** One pricing-breakdown row. {@code percent} is never null; quotes return a non-empty row list. */
public record QuoteRow(String name, PricingRowType rowType, BigDecimal percent) {}
