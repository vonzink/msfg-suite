package com.msfg.los.disclosures.tolerance;

import static com.msfg.los.disclosures.domain.ToleranceBucket.TEN_PERCENT;
import static com.msfg.los.disclosures.domain.ToleranceBucket.UNLIMITED;
import static com.msfg.los.disclosures.domain.ToleranceBucket.ZERO;
import static org.assertj.core.api.Assertions.assertThat;

import com.msfg.los.disclosures.domain.DisclosureCostRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TolerancePolicyTest {

    private final TolerancePolicy policy = new TolerancePolicy();

    // ---- bucket() rules (1026.19(e)(3)(i)-(iii)) ----

    @Test
    void sectionA_isZeroTolerance() {
        assertThat(policy.bucket("A", "UNAFFILIATED", true, true)).isEqualTo(ZERO);
    }

    @Test
    void paidToCreditor_isZeroTolerance() {
        assertThat(policy.bucket("F", "CREDITOR", true, false)).isEqualTo(ZERO);
    }

    @Test
    void sectionE_government_isZeroTolerance() {
        assertThat(policy.bucket("E", "GOVERNMENT", null, null)).isEqualTo(ZERO);
    }

    @Test
    void sectionE_unaffiliated_isTenPercent() {
        assertThat(policy.bucket("E", "UNAFFILIATED", null, null)).isEqualTo(TEN_PERCENT);
    }

    @Test
    void nonShoppable_unaffiliatedThirdParty_isZeroTolerance() {
        assertThat(policy.bucket("B", "UNAFFILIATED", false, null)).isEqualTo(ZERO);
    }

    @Test
    void shoppable_onWrittenList_isTenPercent() {
        assertThat(policy.bucket("C", "UNAFFILIATED", true, true)).isEqualTo(TEN_PERCENT);
    }

    @Test
    void shoppable_didNotShop_isTenPercent() {
        assertThat(policy.bucket("C", "UNAFFILIATED", true, null)).isEqualTo(TEN_PERCENT);
    }

    @Test
    void shoppable_offWrittenList_isUnlimited() {
        assertThat(policy.bucket("C", "UNAFFILIATED", true, false)).isEqualTo(UNLIMITED);
    }

    @Test
    void sectionF_prepaids_isUnlimited() {
        assertThat(policy.bucket("F", "UNAFFILIATED", null, null)).isEqualTo(UNLIMITED);
    }

    @Test
    void default_isUnlimited() {
        assertThat(policy.bucket(null, null, null, null)).isEqualTo(UNLIMITED);
    }

    // ---- compare() good-faith scenario ----

    @Test
    void compare_computesZeroPerItemAndTenPercentCumulativeExcess() {
        List<DisclosureCostRow> baseline = List.of(
                new DisclosureCostRow("E", "Recording", new BigDecimal("100"), TEN_PERCENT),
                new DisclosureCostRow("A", "Origination", new BigDecimal("500"), ZERO));
        List<DisclosureCostRow> current = List.of(
                new DisclosureCostRow("E", "Recording", new BigDecimal("120"), TEN_PERCENT),
                new DisclosureCostRow("A", "Origination", new BigDecimal("560"), ZERO));

        ToleranceComparison result = policy.compare(baseline, current);

        assertThat(result.zeroBucketExcess()).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(result.tenPercentBaselineSum()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(result.tenPercentCurrentSum()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(result.tenPercentExcess()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(result.withinTolerance()).isFalse();
    }
}
