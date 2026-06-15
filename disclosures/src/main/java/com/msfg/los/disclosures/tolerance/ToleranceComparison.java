package com.msfg.los.disclosures.tolerance;

import java.math.BigDecimal;

public record ToleranceComparison(BigDecimal zeroBucketExcess, BigDecimal tenPercentBaselineSum,
        BigDecimal tenPercentCurrentSum, BigDecimal tenPercentExcess, boolean withinTolerance) {}
