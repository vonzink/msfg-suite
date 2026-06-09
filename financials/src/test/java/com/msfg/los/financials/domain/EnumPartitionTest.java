package com.msfg.los.financials.domain;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class EnumPartitionTest {
    @Test void assetAccountPartition() {
        assertThat(AssetType.CHECKING.isAccount()).isTrue();
        assertThat(AssetType.RETIREMENT.isAccount()).isTrue();
        assertThat(AssetType.GIFT.isAccount()).isFalse();
        assertThat(AssetType.EARNEST_MONEY.isAccount()).isFalse();
    }
    @Test void liabilityExpensePartition() {
        assertThat(LiabilityType.REVOLVING.isExpense()).isFalse();
        assertThat(LiabilityType.MORTGAGE_LOAN.isExpense()).isFalse();
        assertThat(LiabilityType.ALIMONY.isExpense()).isTrue();
        assertThat(LiabilityType.CHILD_SUPPORT.isExpense()).isTrue();
    }
}
