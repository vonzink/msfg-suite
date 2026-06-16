package com.msfg.los.qualification.math;

import static org.assertj.core.api.Assertions.assertThat;

import com.msfg.los.loan.domain.LoanPurposeType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pure-logic unit tests for the qualification branchy decisions extracted from
 * {@code LoanCalculationService} (audit C3). Mirrors the matrix in the Spec 6B edge-case table:
 * loan purpose × which value present × null LTV base × negative/zero rental × zero income ×
 * null proposed-housing.
 *
 * <p>{@code null} is encoded as a literal {@code "null"} token in {@code @CsvSource} and decoded
 * by {@link #bd(String)}.
 */
class LoanCalcRulesTest {

    private static BigDecimal bd(String s) {
        return (s == null || s.equals("null")) ? null : new BigDecimal(s);
    }

    @Nested
    class LtvBasis {

        // purpose, salesPrice, appraised, estimated -> expected basis (non-null cases only;
        // null-basis branches covered by the dedicated @Test methods below)
        @ParameterizedTest
        @CsvSource({
                // PURCHASE: lesser of sales/appraised; single-null fallback
                "PURCHASE,    375000, 380000, 999999, 375000",  // lesser is sales
                "PURCHASE,    400000, 380000, 999999, 380000",  // lesser is appraised
                "PURCHASE,    null,   380000, 999999, 380000",  // sales null -> appraised
                "PURCHASE,    375000, null,   999999, 375000",  // appraised null -> sales
                "PURCHASE,    375000, 375000, 999999, 375000",  // equal -> either
                // non-PURCHASE: appraised, fallback estimated (sales ignored)
                "REFINANCE,   375000, 380000, 999999, 380000",  // appraised wins
                "REFINANCE,   375000, null,   350000, 350000",  // appraised null -> estimated
                "CONSTRUCTION,null,   null,   350000, 350000",  // estimated fallback
                "OTHER,       null,   400000, null,   400000",  // appraised wins
        })
        void byPurpose(LoanPurposeType purpose, String sales, String appraised,
                       String estimated, String expected) {
            assertThat(LoanCalcRules.computeLtvBasis(purpose, bd(sales), bd(appraised), bd(estimated)))
                    .isEqualByComparingTo(bd(expected));
        }

        // Null comparison can't use isEqualByComparingTo — assert null branches explicitly.
        @Test
        void purchaseBothNull_isNull() {
            assertThat(LoanCalcRules.computeLtvBasis(LoanPurposeType.PURCHASE, null, null,
                    new BigDecimal("999999"))).isNull();
        }

        @Test
        void refinanceBothNull_isNull() {
            assertThat(LoanCalcRules.computeLtvBasis(LoanPurposeType.REFINANCE,
                    new BigDecimal("375000"), null, null)).isNull();
        }

        // null purpose is treated as non-PURCHASE (appraised then estimated).
        @ParameterizedTest
        @CsvSource({
                "400000, null,   400000",
                "null,   350000, 350000",
        })
        void nullPurpose_treatedAsNonPurchase(String appraised, String estimated, String expected) {
            assertThat(LoanCalcRules.computeLtvBasis(null, new BigDecimal("375000"),
                    bd(appraised), bd(estimated))).isEqualByComparingTo(bd(expected));
        }
    }

    @Nested
    class NetRentalSplit {

        // gross, mortgage -> [income, debt]; net = 0.75*gross - mortgage
        @ParameterizedTest
        @CsvSource({
                // positive net -> income, zero debt
                "2000, 1000, 500.00, 0.00",   // 0.75*2000=1500 - 1000 = 500
                // negative net -> zero income, abs(net) debt
                "1000, 1500, 0.00,   750.00", // 0.75*1000=750 - 1500 = -750
                // zero net -> both zero
                "2000, 1500, 0.00,   0.00",   // 0.75*2000=1500 - 1500 = 0
                // zero rental, with mortgage -> all debt
                "0,    800,  0.00,   800.00",
                // zero rental, zero mortgage -> both zero
                "0,    0,    0.00,   0.00",
        })
        void split(String gross, String mortgage, String expIncome, String expDebt) {
            BigDecimal[] r = LoanCalcRules.netRentalSplit(bd(gross), bd(mortgage));
            assertThat(r[0]).as("income").isEqualByComparingTo(expIncome);
            assertThat(r[1]).as("debt").isEqualByComparingTo(expDebt);
        }

        @Test
        void nullGross_treatedAsZero_allMortgageIsDebt() {
            BigDecimal[] r = LoanCalcRules.netRentalSplit(null, new BigDecimal("600"));
            assertThat(r[0]).isEqualByComparingTo("0");
            assertThat(r[1]).isEqualByComparingTo("600");
        }

        @Test
        void nullMortgage_treatedAsZero_allRentalIsIncome() {
            BigDecimal[] r = LoanCalcRules.netRentalSplit(new BigDecimal("2000"), null);
            assertThat(r[0]).isEqualByComparingTo("1500"); // 0.75 * 2000
            assertThat(r[1]).isEqualByComparingTo("0");
        }

        @Test
        void bothNull_bothZero() {
            BigDecimal[] r = LoanCalcRules.netRentalSplit(null, null);
            assertThat(r[0]).isEqualByComparingTo("0");
            assertThat(r[1]).isEqualByComparingTo("0");
        }
    }

    @Nested
    class FrontEndDti {

        // proposedHousing, totalIncome -> expected (percent scale 3) or null
        @ParameterizedTest
        @CsvSource({
                "2506.20, 9300, 26.948",
                "1500,    6000, 25.000",
        })
        void computed(String housing, String income, String expected) {
            assertThat(LoanCalcRules.frontEndDti(bd(housing), bd(income)))
                    .isEqualByComparingTo(expected);
        }

        @Test
        void nullProposedHousing_isNull() {
            assertThat(LoanCalcRules.frontEndDti(null, new BigDecimal("9300"))).isNull();
        }

        @Test
        void zeroIncome_isNull() {
            assertThat(LoanCalcRules.frontEndDti(new BigDecimal("1500"), BigDecimal.ZERO)).isNull();
        }

        @Test
        void nullIncome_isNull() {
            assertThat(LoanCalcRules.frontEndDti(new BigDecimal("1500"), null)).isNull();
        }
    }

    @Nested
    class BackEndDti {

        // proposedHousing, totalDebts, totalIncome -> expected or null
        @ParameterizedTest
        @CsvSource({
                "1500, 500,  6000, 33.333",  // (1500+500)/6000
                "1500, null, 6000, 25.000",  // null debts -> treated as zero
                "1500, 0,    6000, 25.000",
        })
        void computed(String housing, String debts, String income, String expected) {
            assertThat(LoanCalcRules.backEndDti(bd(housing), bd(debts), bd(income)))
                    .isEqualByComparingTo(expected);
        }

        @Test
        void nullProposedHousing_isNull() {
            assertThat(LoanCalcRules.backEndDti(null, new BigDecimal("500"),
                    new BigDecimal("6000"))).isNull();
        }

        @Test
        void zeroIncome_isNull() {
            assertThat(LoanCalcRules.backEndDti(new BigDecimal("1500"), new BigDecimal("500"),
                    BigDecimal.ZERO)).isNull();
        }

        @Test
        void nullIncome_isNull() {
            assertThat(LoanCalcRules.backEndDti(new BigDecimal("1500"), new BigDecimal("500"),
                    null)).isNull();
        }
    }

    @Nested
    class HousingDelta {

        @Test
        void bothPresent_subtractsMoneyScaled() {
            assertThat(LoanCalcRules.housingDelta(new BigDecimal("1500.005"),
                    new BigDecimal("1200"))).isEqualByComparingTo("300.01"); // HALF_UP
        }

        @Test
        void nullPresent_isNull() {
            assertThat(LoanCalcRules.housingDelta(new BigDecimal("1500"), null)).isNull();
        }

        @Test
        void nullProposed_isNull() {
            assertThat(LoanCalcRules.housingDelta(null, new BigDecimal("1200"))).isNull();
        }

        @Test
        void bothNull_isNull() {
            assertThat(LoanCalcRules.housingDelta(null, null)).isNull();
        }
    }

    /** Sanity: every purpose enum value is handled without throwing. */
    @ParameterizedTest
    @EnumSource(LoanPurposeType.class)
    void everyPurpose_handledNoThrow(LoanPurposeType purpose) {
        LoanCalcRules.computeLtvBasis(purpose, new BigDecimal("100"),
                new BigDecimal("200"), new BigDecimal("300"));
    }
}
