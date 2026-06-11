package com.msfg.los.fees.web.dto;

import com.msfg.los.fees.domain.FeeSection;

import java.math.BigDecimal;

public record AddFeeRequest(
        FeeSection section,
        String label,
        BigDecimal amount,
        BigDecimal sellerConcession,
        BigDecimal percent) {}
