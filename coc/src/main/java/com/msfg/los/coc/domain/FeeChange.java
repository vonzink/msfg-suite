package com.msfg.los.coc.domain;

import java.math.BigDecimal;

public record FeeChange(
        String section,
        String label,
        BigDecimal currentValue,
        BigDecimal requestedValue,
        String reason,
        String hasInvoice
) {}
