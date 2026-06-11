package com.msfg.los.fees.web.dto;

import java.math.BigDecimal;

public record UpdateFeeRequest(
        BigDecimal amount,
        BigDecimal sellerConcession,
        BigDecimal percent) {}
