package com.msfg.los.pricing.port;

/** External pricing engine seam. Stub adapter today; real vendor (Optimal Blue et al.) later. */
public interface PricingEnginePort {
    PriceQuote quote(PricingQuoteRequest request);
}
