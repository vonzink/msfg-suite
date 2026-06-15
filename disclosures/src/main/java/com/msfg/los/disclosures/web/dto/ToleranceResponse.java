package com.msfg.los.disclosures.web.dto;

import com.msfg.los.disclosures.tolerance.ToleranceComparison;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Current good-faith tolerance view: per-bucket totals of the live fee set (keyed by
 * {@link com.msfg.los.disclosures.domain.ToleranceBucket} name) plus the comparison of the current
 * cost set against the baseline (original) LE snapshot — null when no LE has been issued yet.
 */
public record ToleranceResponse(
        Map<String, BigDecimal> bucketTotals,
        ToleranceComparison comparisonVsBaselineLe) {}
