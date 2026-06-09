package com.msfg.los.qualification.math;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Pure mortgage-math helpers. Currency → scale 2 HALF_UP; ratios → percent scale 3 HALF_UP. */
public final class MortgageMath {
    private MortgageMath() {}

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal RATE_DIVISOR = new BigDecimal("1200"); // /100 (percent) /12 (months)

    /** Monthly principal+interest. Args must be non-null and term &gt; 0 (caller guards). */
    public static BigDecimal monthlyPrincipalInterest(BigDecimal principal, BigDecimal annualRatePercent, int termMonths) {
        BigDecimal c = annualRatePercent.divide(RATE_DIVISOR, MC);            // monthly rate (decimal)
        if (c.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP);
        }
        BigDecimal pow = BigDecimal.ONE.add(c).pow(termMonths, MC);           // (1+c)^n
        BigDecimal numerator = principal.multiply(c, MC).multiply(pow, MC);
        BigDecimal denominator = pow.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    /** numerator/denominator × 100, scale 3 HALF_UP. null if either null or denominator == 0. */
    public static BigDecimal percentRatio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return null;
        return numerator.divide(denominator, 10, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(3, RoundingMode.HALF_UP);
    }

    /** Currency scale 2 HALF_UP; null-safe. */
    public static BigDecimal money(BigDecimal x) {
        return x == null ? null : x.setScale(2, RoundingMode.HALF_UP);
    }

    /** null → ZERO (for additive aggregation). */
    public static BigDecimal nz(BigDecimal x) { return x == null ? BigDecimal.ZERO : x; }
}
