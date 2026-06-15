package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.domain.CashToCloseRow;
import com.msfg.los.disclosures.domain.DisclosureCostRow;
import com.msfg.los.disclosures.domain.DisclosureKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DisclosureGenerationRequest(
        DisclosureKind kind,
        UUID loanId,
        int version,
        String loanNumber,
        BigDecimal loanAmount,
        BigDecimal interestRate,
        Integer termMonths,
        BigDecimal monthlyPrincipalInterest,
        BigDecimal totalClosingCosts,
        BigDecimal prepaidFinanceCharges,
        boolean prepaymentPenalty,
        String productDescription,
        List<DisclosureCostRow> costTable,
        List<CashToCloseRow> cashToClose) {}
