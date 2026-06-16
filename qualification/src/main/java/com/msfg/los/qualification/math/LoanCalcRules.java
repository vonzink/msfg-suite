package com.msfg.los.qualification.math;

import com.msfg.los.loan.domain.LoanPurposeType;

import java.math.BigDecimal;

import static com.msfg.los.qualification.math.MortgageMath.money;
import static com.msfg.los.qualification.math.MortgageMath.nz;
import static com.msfg.los.qualification.math.MortgageMath.percentRatio;

/**
 * Pure branchy decisions of the qualification engine (Spec 6B), extracted from
 * {@code LoanCalculationService} so they can be unit-tested without Spring/DB.
 *
 * <p>No state, no I/O — every method is a deterministic function of its arguments and produces
 * results identical to the inline logic it replaced (behavior-preserving refactor). Missing inputs
 * return {@code null} for the dependent figure; aggregation never divides by zero.
 */
public final class LoanCalcRules {

    private LoanCalcRules() {}

    static final BigDecimal SEVENTY_FIVE_PCT = new BigDecimal("0.75");

    /**
     * LTV basis per spec §2:
     * <ul>
     *   <li>PURCHASE → lesser of salesPrice/appraisedValue; if one is null use the other; both null → null.
     *   <li>non-PURCHASE (REFINANCE / CONSTRUCTION / OTHER / null purpose) → appraisedValue;
     *       fallback estimatedValue; else null.
     * </ul>
     */
    public static BigDecimal computeLtvBasis(
            LoanPurposeType purpose,
            BigDecimal salesPrice,
            BigDecimal appraised,
            BigDecimal estimated) {

        if (purpose == LoanPurposeType.PURCHASE) {
            if (salesPrice == null && appraised == null) return null;
            if (salesPrice == null) return appraised;
            if (appraised == null) return salesPrice;
            return salesPrice.min(appraised);
        } else {
            if (appraised != null) return appraised;
            return estimated; // null if both absent
        }
    }

    /**
     * Fannie 75%-convention net rental for a single REO row:
     * {@code net = 0.75 × grossMonthlyRentalIncome − mortgageMonthlyPayment}.
     * Positive net → income; negative net → its absolute value is debt.
     *
     * @return [income, debt] each ≥ 0 — exactly one is non-zero (or both zero when net == 0).
     */
    public static BigDecimal[] netRentalSplit(BigDecimal grossMonthlyRentalIncome,
                                              BigDecimal mortgageMonthlyPayment) {
        BigDecimal net = SEVENTY_FIVE_PCT.multiply(nz(grossMonthlyRentalIncome))
                .subtract(nz(mortgageMonthlyPayment));
        BigDecimal income = net.max(BigDecimal.ZERO);
        BigDecimal debt = net.negate().max(BigDecimal.ZERO);
        return new BigDecimal[] {income, debt};
    }

    /**
     * Front-end (housing) DTI: {@code proposedHousing / totalIncome × 100}.
     * Null when proposedHousing is null or totalIncome is null/zero (percentRatio guards the denominator).
     */
    public static BigDecimal frontEndDti(BigDecimal proposedHousing, BigDecimal totalIncome) {
        return percentRatio(proposedHousing, totalIncome);
    }

    /**
     * Back-end DTI: {@code (proposedHousing + totalDebts) / totalIncome × 100}.
     * Null when proposedHousing is null (housing is the mandatory component) or totalIncome
     * is null/zero. totalDebts null is treated as zero.
     */
    public static BigDecimal backEndDti(BigDecimal proposedHousing, BigDecimal totalDebts,
                                        BigDecimal totalIncome) {
        if (proposedHousing == null) return null;
        return percentRatio(proposedHousing.add(nz(totalDebts)), totalIncome);
    }

    /**
     * Housing delta: {@code proposedHousing − presentHousing}, money-scaled.
     * Null unless BOTH operands are present.
     */
    public static BigDecimal housingDelta(BigDecimal proposedHousing, BigDecimal presentHousing) {
        return (proposedHousing != null && presentHousing != null)
                ? money(proposedHousing.subtract(presentHousing))
                : null;
    }
}
