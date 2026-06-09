package com.msfg.los.qualification.math;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class MortgageMathTest {

    @Test void monthlyPI_standard() {
        BigDecimal pi = MortgageMath.monthlyPrincipalInterest(new BigDecimal("300000"), new BigDecimal("6.5"), 360);
        assertThat(pi).isEqualByComparingTo("1896.20");   // 300k @ 6.5% / 30yr
    }

    @Test void monthlyPI_zeroRate() {
        BigDecimal pi = MortgageMath.monthlyPrincipalInterest(new BigDecimal("360000"), BigDecimal.ZERO, 360);
        assertThat(pi).isEqualByComparingTo("1000.00");   // principal / term
    }

    @Test void percentRatio_basic() {
        assertThat(MortgageMath.percentRatio(new BigDecimal("2506.20"), new BigDecimal("9300")))
            .isEqualByComparingTo("26.948");
    }

    @Test void percentRatio_nullOrZeroDenominator() {
        assertThat(MortgageMath.percentRatio(new BigDecimal("100"), BigDecimal.ZERO)).isNull();
        assertThat(MortgageMath.percentRatio(new BigDecimal("100"), null)).isNull();
        assertThat(MortgageMath.percentRatio(null, new BigDecimal("100"))).isNull();
    }

    @Test void money_rounds() {
        assertThat(MortgageMath.money(new BigDecimal("1.005"))).isEqualByComparingTo("1.01"); // HALF_UP
        assertThat(MortgageMath.money(null)).isNull();
    }
}
