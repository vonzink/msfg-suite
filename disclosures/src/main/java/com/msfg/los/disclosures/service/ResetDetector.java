package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.domain.DisclosureIssuance;
import com.msfg.los.disclosures.domain.ResetReason;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects the TRID conditions that require re-disclosing a Closing Disclosure (a "reset" of the
 * good-faith / accuracy clock) by comparing a freshly-generated CD against the prior issued CD.
 *
 * <p>Three triggers are evaluated:
 * <ul>
 *   <li><b>APR_INACCURATE</b> — the disclosed APR is no longer "accurate" within the
 *       {@code 12 CFR 1026.22(a)} tolerance band ({@code ±0.125} for a regular transaction,
 *       {@code ±0.25} when the APR is computed on an irregular basis, per {@code 1026.22(a)(3)}).
 *       A finance charge that is itself inaccurate triggers a corrected disclosure under
 *       {@code 1026.19(f)(2)(ii)}; the APR is the cleaner observable proxy here.</li>
 *   <li><b>PRODUCT_CHANGED</b> — the loan product description changed.</li>
 *   <li><b>PREPAYMENT_PENALTY_ADDED</b> — a prepayment penalty was added that the prior CD did not
 *       disclose.</li>
 * </ul>
 *
 * <p><b>APR model (conservative):</b> we re-disclose whenever the APR moves outside the band, with a
 * single carve-out for the consumer-protective overstatement relief of {@code 1026.22(a)(5)}: a
 * disclosed APR that was <i>overstated</i> (the actual APR is lower than what we told the consumer)
 * is deemed accurate — and so does NOT require a corrected disclosure — when the related finance
 * charge was also overstated ({@code 1026.22(a)(5)(ii)}). In every other case (any understatement
 * beyond the band, or an overstatement where the finance charge was understated) we conservatively
 * flag APR_INACCURATE and re-disclose. {@code delta} is actual minus disclosed
 * (new APR − prior CD's APR), so a negative delta is an overstatement.
 */
@Component
public class ResetDetector {

    private static final BigDecimal BAND_REGULAR = new BigDecimal("0.125");
    private static final BigDecimal BAND_IRREGULAR = new BigDecimal("0.25");

    public List<ResetReason> detect(DisclosureIssuance priorCd, DisclosureGenerationResult newResult,
            String newProduct, boolean newPrepaymentPenalty) {
        List<ResetReason> reasons = new ArrayList<>();

        // ── APR accuracy: 1026.22(a) symmetric tolerance band, with a5(ii) overstatement relief ──
        // Defense-in-depth: an ERROR-row prior CD carries a null APR/finance-charge. If either side
        // is null we cannot measure APR accuracy — skip the APR check (still evaluate product +
        // prepay below). The service-layer fix already excludes ERROR rows from the prior-CD lookup;
        // this guards the detector itself against a null reaching it by any path.
        boolean aprComparable = priorCd.getApr() != null && newResult.apr() != null;
        BigDecimal band = newResult.aprIrregularBasis() ? BAND_IRREGULAR : BAND_REGULAR;
        BigDecimal delta = aprComparable
                ? newResult.apr().subtract(priorCd.getApr()) : null; // actual − disclosed
        if (aprComparable && delta.abs().compareTo(band) > 0) {
            // Outside the band. Re-disclose UNLESS pure overstatement relief clearly applies:
            // disclosed APR was overstated (delta < 0) AND the finance charge was also overstated
            // (new finance charge <= prior CD's) → deemed accurate per 1026.22(a)(5)(ii).
            boolean overstatementRelief = delta.signum() < 0
                    && newResult.financeCharge().compareTo(priorCd.getFinanceCharge()) <= 0;
            if (!overstatementRelief) {
                reasons.add(ResetReason.APR_INACCURATE);
            }
        }

        // ── Product changed ──
        if (newProduct != null && !newProduct.equals(priorCd.getProductDescription())) {
            reasons.add(ResetReason.PRODUCT_CHANGED);
        }

        // ── Prepayment penalty added (prior did not disclose one) ──
        if (newPrepaymentPenalty && !priorCd.isPrepaymentPenalty()) {
            reasons.add(ResetReason.PREPAYMENT_PENALTY_ADDED);
        }

        return reasons;
    }
}
