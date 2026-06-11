package com.msfg.los.pricing.port;

import java.util.List;

public record PriceQuote(List<QuoteRow> rows) {}
