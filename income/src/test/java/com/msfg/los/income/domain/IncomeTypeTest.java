package com.msfg.los.income.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IncomeTypeTest {
    @Test void employmentTypesArePartitioned() {
        assertThat(IncomeType.BASE.isEmployment()).isTrue();
        assertThat(IncomeType.OVERTIME.isEmployment()).isTrue();
        assertThat(IncomeType.SELF_EMPLOYMENT_INCOME.isEmployment()).isTrue();
        assertThat(IncomeType.OTHER_EMPLOYMENT.isEmployment()).isTrue();
        assertThat(IncomeType.SOCIAL_SECURITY.isEmployment()).isFalse();
        assertThat(IncomeType.CHILD_SUPPORT.isEmployment()).isFalse();
        assertThat(IncomeType.OTHER.isEmployment()).isFalse();
    }
    @Test void selfEmploymentMayBeNegativeOthersMayNot() {
        assertThat(IncomeType.SELF_EMPLOYMENT_INCOME.allowsNegative()).isTrue();
        assertThat(IncomeType.BASE.allowsNegative()).isFalse();
        assertThat(IncomeType.SOCIAL_SECURITY.allowsNegative()).isFalse();
    }
}
