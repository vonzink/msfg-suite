package com.msfg.los.platform.id;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LoanNumberGeneratorTest {
    @Test void formatsZeroPadded10Digits() {
        LoanNumberGenerator gen = seq -> String.format("%010d", seq);
        assertThat(gen.format(42L)).isEqualTo("0000000042");
        assertThat(gen.format(42L)).hasSize(10);
    }
}
