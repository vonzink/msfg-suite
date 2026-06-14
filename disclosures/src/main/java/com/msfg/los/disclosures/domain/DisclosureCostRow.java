package com.msfg.los.disclosures.domain;

import java.math.BigDecimal;

/** One cost line snapshotted at issuance, tagged with its TRID tolerance bucket. */
public record DisclosureCostRow(String section, String label, BigDecimal amount, ToleranceBucket bucket) {}
