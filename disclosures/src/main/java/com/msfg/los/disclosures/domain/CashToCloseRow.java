package com.msfg.los.disclosures.domain;

import java.math.BigDecimal;

/** One Calculating Cash to Close line snapshotted at issuance. */
public record CashToCloseRow(String label, BigDecimal amount) {}
