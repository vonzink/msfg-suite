package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.domain.DisclosureIssuance;
import com.msfg.los.disclosures.domain.ResetReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResetDetector} — the three CD re-disclosure triggers. The APR band is
 * recomputed independently here (0.125 regular / 0.25 irregular per 1026.22(a)(3)) rather than read
 * from the class under test, so a wrong band in the detector would surface as a failing assertion.
 */
class ResetDetectorTest {

    private static final BigDecimal REGULAR_BAND = new BigDecimal("0.125");

    /** A prior CD with a known disclosed APR / finance charge / product / prepay flag. */
    private DisclosureIssuance priorCd(String apr, String financeCharge, String product, boolean prepay) {
        DisclosureIssuance cd = new DisclosureIssuance();
        cd.setApr(new BigDecimal(apr));
        cd.setFinanceCharge(new BigDecimal(financeCharge));
        cd.setProductDescription(product);
        cd.setPrepaymentPenalty(prepay);
        return cd;
    }

    /** A freshly generated result. Only apr / financeCharge / aprIrregularBasis matter to the detector. */
    private DisclosureGenerationResult gen(String apr, String financeCharge, boolean irregular) {
        return new DisclosureGenerationResult(
                new BigDecimal(apr),
                new BigDecimal(financeCharge),
                BigDecimal.ZERO,   // amountFinanced (unused)
                BigDecimal.ZERO,   // totalOfPayments (unused)
                BigDecimal.ZERO,   // tip (unused)
                irregular,
                new byte[0],
                "text/html",
                "DV-TEST");
    }

    @Test
    void understatementOverBand_triggersAprInaccurate() {
        // disclosed 6.500; actual 6.700 → delta = +0.200 > 0.125 band, understatement → reset.
        BigDecimal delta = new BigDecimal("6.700").subtract(new BigDecimal("6.500"));
        assertThat(delta.abs()).isGreaterThan(REGULAR_BAND); // independent band recompute
        assertThat(delta.signum()).isGreaterThan(0);          // understatement

        List<ResetReason> reasons = new ResetDetector().detect(
                priorCd("6.500", "100000.00", "CONVENTIONAL PURCHASE", false),
                gen("6.700", "100200.00", false),
                "CONVENTIONAL PURCHASE", false);

        assertThat(reasons).containsExactly(ResetReason.APR_INACCURATE);
    }

    @Test
    void withinBand_noReset() {
        // disclosed 6.500; actual 6.600 → delta = +0.100 <= 0.125 band → accurate, no reset.
        BigDecimal delta = new BigDecimal("6.600").subtract(new BigDecimal("6.500"));
        assertThat(delta.abs()).isLessThanOrEqualTo(REGULAR_BAND);

        List<ResetReason> reasons = new ResetDetector().detect(
                priorCd("6.500", "100000.00", "CONVENTIONAL PURCHASE", false),
                gen("6.600", "100500.00", false),
                "CONVENTIONAL PURCHASE", false);

        assertThat(reasons).isEmpty();
    }

    @Test
    void overstatementOverBand_financeChargeAlsoOverstated_reliefApplies_noReset() {
        // disclosed 6.700; actual 6.500 → delta = -0.200 (overstatement), |delta| > 0.125.
        // new finance charge (99000) <= prior (100000) → also overstated → 1026.22(a)(5)(ii) relief.
        BigDecimal delta = new BigDecimal("6.500").subtract(new BigDecimal("6.700"));
        assertThat(delta.abs()).isGreaterThan(REGULAR_BAND);
        assertThat(delta.signum()).isLessThan(0); // overstatement

        List<ResetReason> reasons = new ResetDetector().detect(
                priorCd("6.700", "100000.00", "CONVENTIONAL PURCHASE", false),
                gen("6.500", "99000.00", false),
                "CONVENTIONAL PURCHASE", false);

        assertThat(reasons).isEmpty();
    }

    @Test
    void overstatementOverBand_financeChargeUnderstated_noRelief_triggersAprInaccurate() {
        // disclosed 6.700; actual 6.500 → overstatement, |delta| > band.
        // BUT new finance charge (101000) > prior (100000) → understated FC → relief does NOT apply.
        BigDecimal delta = new BigDecimal("6.500").subtract(new BigDecimal("6.700"));
        assertThat(delta.abs()).isGreaterThan(REGULAR_BAND);
        assertThat(delta.signum()).isLessThan(0);

        List<ResetReason> reasons = new ResetDetector().detect(
                priorCd("6.700", "100000.00", "CONVENTIONAL PURCHASE", false),
                gen("6.500", "101000.00", false),
                "CONVENTIONAL PURCHASE", false);

        assertThat(reasons).containsExactly(ResetReason.APR_INACCURATE);
    }

    @Test
    void productChange_triggersProductChanged() {
        // APR identical → no APR reset; only the product description differs.
        List<ResetReason> reasons = new ResetDetector().detect(
                priorCd("6.500", "100000.00", "CONVENTIONAL PURCHASE", false),
                gen("6.500", "100000.00", false),
                "FHA PURCHASE", false);

        assertThat(reasons).containsExactly(ResetReason.PRODUCT_CHANGED);
    }

    @Test
    void prepaymentPenaltyAdded_priorFalseNewTrue_triggersAdded() {
        List<ResetReason> reasons = new ResetDetector().detect(
                priorCd("6.500", "100000.00", "CONVENTIONAL PURCHASE", false),
                gen("6.500", "100000.00", false),
                "CONVENTIONAL PURCHASE", true);

        assertThat(reasons).containsExactly(ResetReason.PREPAYMENT_PENALTY_ADDED);
    }

    @Test
    void prepaymentPenaltyAlreadyDisclosed_notAdded() {
        // Prior CD already disclosed a prepayment penalty → carrying it forward is NOT an "added" trigger.
        List<ResetReason> reasons = new ResetDetector().detect(
                priorCd("6.500", "100000.00", "CONVENTIONAL PURCHASE", true),
                gen("6.500", "100000.00", false),
                "CONVENTIONAL PURCHASE", true);

        assertThat(reasons).isEmpty();
    }

    /**
     * Defense-in-depth (Blocker 1): a prior CD with a null APR (an ERROR row's shape) must NOT NPE
     * the detector. The APR check is skipped, but product/prepay are still evaluated — here the
     * product changed, so PRODUCT_CHANGED is still flagged and no exception is thrown.
     */
    @Test
    void priorCdNullApr_skipsAprCheck_stillEvaluatesProduct() {
        DisclosureIssuance errorPrior = priorCd("6.500", "100000.00", "CONVENTIONAL PURCHASE", false);
        errorPrior.setApr(null);          // ERROR-row shape
        errorPrior.setFinanceCharge(null);

        List<ResetReason> reasons = new ResetDetector().detect(
                errorPrior,
                gen("6.700", "100200.00", false), // APR delta would be > band IF compared
                "FHA PURCHASE", false);

        assertThat(reasons).containsExactly(ResetReason.PRODUCT_CHANGED);
    }
}
