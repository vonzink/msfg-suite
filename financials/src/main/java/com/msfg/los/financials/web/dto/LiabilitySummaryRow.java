package com.msfg.los.financials.web.dto;

import com.msfg.los.financials.domain.LiabilityType;
import java.math.BigDecimal;
import java.util.UUID;

public record LiabilitySummaryRow(UUID borrowerId, String borrowerName, LiabilityType liabilityType,
                                  String creditorName, BigDecimal monthlyPayment, boolean includeInDti) {}
