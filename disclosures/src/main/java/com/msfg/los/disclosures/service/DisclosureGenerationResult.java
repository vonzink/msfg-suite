package com.msfg.los.disclosures.service;

import java.math.BigDecimal;

public record DisclosureGenerationResult(
        BigDecimal apr,
        BigDecimal financeCharge,
        BigDecimal amountFinanced,
        BigDecimal totalOfPayments,
        BigDecimal tip,
        boolean aprIrregularBasis,
        byte[] renderedBytes,
        String renderedContentType,
        String vendorReference) {}
