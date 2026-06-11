package com.msfg.los.pricing.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class RateLockStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

    @Test
    void expirationAfterToday_isLocked() {
        assertThat(RateLockStatus.effective(TODAY.plusDays(1), TODAY)).isEqualTo(RateLockStatus.LOCKED);
    }

    @Test
    void expirationToday_isStillLocked() {
        assertThat(RateLockStatus.effective(TODAY, TODAY)).isEqualTo(RateLockStatus.LOCKED);
    }

    @Test
    void expirationBeforeToday_isExpired() {
        assertThat(RateLockStatus.effective(TODAY.minusDays(1), TODAY)).isEqualTo(RateLockStatus.EXPIRED);
    }
}
