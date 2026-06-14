package com.msfg.los.disclosures.domain;

import java.math.BigDecimal;
import java.util.List;

/** Immutable point-in-time snapshot of the figures behind an issued disclosure (jsonb payload). */
public record DisclosureSnapshot(
        List<DisclosureCostRow> costRows,
        List<CashToCloseRow> cashToClose,
        BigDecimal loanAmount,
        BigDecimal interestRate,
        Integer termMonths) {}
