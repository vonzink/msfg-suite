package com.msfg.los.disclosures.timing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.msfg.los.disclosures.domain.BusinessDayType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic crown jewel for TRID timing.
 *
 * <p>Convention verified against the primary source, 12 CFR 1026.2(a)(6) and its Official
 * Interpretation (Supplement I), via consumerfinance.gov:
 * https://www.consumerfinance.gov/rules-policy/regulations/1026/2/
 *
 * <ul>
 *   <li>PRECISE ("more precise rule" — used for §1026.19(e)/(f) receipt-of-disclosure timing,
 *       rescission): all calendar days except Sundays and the federal legal holidays in
 *       5 U.S.C. 6103(a). <b>Saturdays count.</b>
 *   <li>GENERAL: a day on which the creditor is open to the public for substantially all of its
 *       business functions.
 *   <li>Four fixed-date holidays (Jan 1, Jul 4, Nov 11, Dec 25) — and likewise Juneteenth, Jun 19,
 *       added to 6103(a) in 2021 — observe Saturday→prior Friday, Sunday→following Monday shifting.
 *   <li>Counting "N business days after" a trigger starts the day AFTER the trigger (the trigger
 *       day itself is day 0): the N-th business day strictly after {@code from}.
 * </ul>
 */
class BusinessDayCalculatorTest {

    private final BusinessDayCalculator calc = new BusinessDayCalculator();
    private final GeneralBusinessDayConfig cfg = GeneralBusinessDayConfig.DEFAULT;

    @Nested
    class FederalHolidays {

        @Test
        void observedHolidays2026_areHolidays() {
            // 5 U.S.C. 6103(a), with observed shifting, for calendar year 2026.
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 1, 1))).isTrue();  // New Year's Day (Thu)
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 1, 19))).isTrue(); // MLK (3rd Mon Jan)
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 2, 16))).isTrue(); // Washington (3rd Mon Feb)
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 5, 25))).isTrue(); // Memorial (last Mon May)
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 6, 19))).isTrue(); // Juneteenth (Fri)
            // Independence Day Jul 4 2026 is Saturday -> observed Fri Jul 3.
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 7, 3))).isTrue();
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 9, 7))).isTrue();  // Labor (1st Mon Sep)
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 10, 12))).isTrue();// Columbus (2nd Mon Oct)
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 11, 11))).isTrue();// Veterans (Wed)
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 11, 26))).isTrue();// Thanksgiving (4th Thu Nov)
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 12, 25))).isTrue();// Christmas (Fri)
        }

        @Test
        void actualSaturdayJul4_isNotTheObservedHoliday() {
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 7, 4))).isFalse();
        }

        @Test
        void ordinaryDay_isNotHoliday() {
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 6, 15))).isFalse(); // Mon
            assertThat(calc.isFederalHoliday(LocalDate.of(2026, 6, 20))).isFalse(); // Sat
        }

        @Test
        void sundayHoliday_observedFollowingMonday() {
            // Jul 4 2021 was a Sunday -> observed Mon Jul 5 2021.
            assertThat(calc.isFederalHoliday(LocalDate.of(2021, 7, 4))).isFalse();
            assertThat(calc.isFederalHoliday(LocalDate.of(2021, 7, 5))).isTrue();
        }

        @Test
        void saturdayHoliday_observedPriorFriday() {
            // Dec 25 2021 was a Saturday -> observed Fri Dec 24 2021.
            assertThat(calc.isFederalHoliday(LocalDate.of(2021, 12, 25))).isFalse();
            assertThat(calc.isFederalHoliday(LocalDate.of(2021, 12, 24))).isTrue();
        }
    }

    @Nested
    class ConfigValidation {

        @Test
        void emptyOpenDays_rejected() {
            // An empty open-day set is nonsensical: no business day would ever exist, so
            // addBusinessDays(..., GENERAL, cfg) would loop forever. Reject at the source.
            assertThatThrownBy(
                            () ->
                                    new GeneralBusinessDayConfig(
                                            EnumSet.noneOf(DayOfWeek.class)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullOpenDays_rejected() {
            assertThatThrownBy(() -> new GeneralBusinessDayConfig(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class IsBusinessDay {

        @Test
        void precise_saturdayCounts() {
            assertThat(calc.isBusinessDay(LocalDate.of(2026, 6, 20), BusinessDayType.PRECISE, cfg))
                    .isTrue(); // Sat
        }

        @Test
        void general_saturdayDoesNotCount() {
            assertThat(calc.isBusinessDay(LocalDate.of(2026, 6, 20), BusinessDayType.GENERAL, cfg))
                    .isFalse(); // Sat not in default open days
        }

        @Test
        void precise_sundayNeverCounts() {
            assertThat(calc.isBusinessDay(LocalDate.of(2026, 6, 21), BusinessDayType.PRECISE, cfg))
                    .isFalse(); // Sun
        }

        @Test
        void precise_observedHolidayDoesNotCount() {
            assertThat(calc.isBusinessDay(LocalDate.of(2026, 6, 19), BusinessDayType.PRECISE, cfg))
                    .isFalse(); // Juneteenth (Fri)
        }

        @Test
        void general_observedHolidayDoesNotCount() {
            assertThat(calc.isBusinessDay(LocalDate.of(2026, 6, 19), BusinessDayType.GENERAL, cfg))
                    .isFalse(); // Juneteenth (Fri)
        }

        @Test
        void general_weekdayCounts() {
            assertThat(calc.isBusinessDay(LocalDate.of(2026, 6, 15), BusinessDayType.GENERAL, cfg))
                    .isTrue(); // Mon
        }

        @Test
        void general_respectsCustomOpenDays_saturdayOpenSundayClosed() {
            GeneralBusinessDayConfig sixDay =
                    new GeneralBusinessDayConfig(
                            EnumSet.of(
                                    DayOfWeek.MONDAY,
                                    DayOfWeek.TUESDAY,
                                    DayOfWeek.WEDNESDAY,
                                    DayOfWeek.THURSDAY,
                                    DayOfWeek.FRIDAY,
                                    DayOfWeek.SATURDAY));
            assertThat(calc.isBusinessDay(LocalDate.of(2026, 6, 20), BusinessDayType.GENERAL, sixDay))
                    .isTrue(); // Sat now open
            // Holiday still excluded even when the weekday is open.
            assertThat(calc.isBusinessDay(LocalDate.of(2026, 6, 19), BusinessDayType.GENERAL, sixDay))
                    .isFalse(); // Juneteenth
        }
    }

    @Nested
    class AddBusinessDays {

        @Test
        void zero_returnsSameDate() {
            LocalDate d = LocalDate.of(2026, 6, 15);
            assertThat(calc.addBusinessDays(d, 0, BusinessDayType.PRECISE, cfg)).isEqualTo(d);
            assertThat(calc.addBusinessDays(d, 0, BusinessDayType.GENERAL, cfg)).isEqualTo(d);
        }

        @Test
        void precise_threeFromMonday_noHolidayInWindow() {
            // Mon 15 -> Tue16, Wed17, Thu18.
            assertThat(
                            calc.addBusinessDays(
                                    LocalDate.of(2026, 6, 15), 3, BusinessDayType.PRECISE, cfg))
                    .isEqualTo(LocalDate.of(2026, 6, 18));
        }

        @Test
        void precise_threeFromWednesday_skipsJuneteenthAndSunday_countsSaturday() {
            // Wed17 -> Thu18(1); Fri19 Juneteenth skip; Sat20(2) counts; Sun21 skip; Mon22(3).
            assertThat(
                            calc.addBusinessDays(
                                    LocalDate.of(2026, 6, 17), 3, BusinessDayType.PRECISE, cfg))
                    .isEqualTo(LocalDate.of(2026, 6, 22));
        }

        @Test
        void general_threeFromMonday() {
            // Mon15 -> Tue16, Wed17, Thu18 (Sat/Sun closed, no holiday).
            assertThat(
                            calc.addBusinessDays(
                                    LocalDate.of(2026, 6, 15), 3, BusinessDayType.GENERAL, cfg))
                    .isEqualTo(LocalDate.of(2026, 6, 18));
        }

        @Test
        void precise_sevenFromMonday_acrossJuneteenthWeekend() {
            // Tue16,Wed17,Thu18, skip Fri19, Sat20, skip Sun21, Mon22, Tue23, Wed24 => Wed24 is the 7th.
            assertThat(
                            calc.addBusinessDays(
                                    LocalDate.of(2026, 6, 15), 7, BusinessDayType.PRECISE, cfg))
                    .isEqualTo(LocalDate.of(2026, 6, 24));
        }

        @Test
        void general_sevenFromMonday_skipsBothWeekendDaysAndJuneteenth() {
            // Open days Mon-Fri. From Mon15: Tue16,Wed17,Thu18, skip Fri19(holiday),
            // skip Sat20+Sun21, Mon22,Tue23,Wed24(=6), Thu25(=7) -> Thu Jun 25.
            assertThat(
                            calc.addBusinessDays(
                                    LocalDate.of(2026, 6, 15), 7, BusinessDayType.GENERAL, cfg))
                    .isEqualTo(LocalDate.of(2026, 6, 25));
        }

        @Test
        void negativeN_rejected() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            calc.addBusinessDays(
                                    LocalDate.of(2026, 6, 15), -1, BusinessDayType.PRECISE, cfg));
        }
    }

    @Nested
    class BusinessDaysBetween {

        @Test
        void countsInHalfOpenInterval_excludesStartIncludesEnd() {
            // (Mon15, Thu18] precise = Tue16,Wed17,Thu18 = 3.
            assertThat(
                            calc.businessDaysBetween(
                                    LocalDate.of(2026, 6, 15),
                                    LocalDate.of(2026, 6, 18),
                                    BusinessDayType.PRECISE,
                                    cfg))
                    .isEqualTo(3);
        }

        @Test
        void precise_acrossJuneteenthWeekend_countsSaturday() {
            // (Wed17, Mon22] precise = Thu18, Sat20, Mon22 (skip Fri19 holiday, Sun21) = 3.
            assertThat(
                            calc.businessDaysBetween(
                                    LocalDate.of(2026, 6, 17),
                                    LocalDate.of(2026, 6, 22),
                                    BusinessDayType.PRECISE,
                                    cfg))
                    .isEqualTo(3);
        }

        @Test
        void general_acrossJuneteenthWeekend_excludesSaturday() {
            // (Wed17, Mon22] general = Thu18, Mon22 (skip Fri19 holiday, Sat20, Sun21) = 2.
            assertThat(
                            calc.businessDaysBetween(
                                    LocalDate.of(2026, 6, 17),
                                    LocalDate.of(2026, 6, 22),
                                    BusinessDayType.GENERAL,
                                    cfg))
                    .isEqualTo(2);
        }

        @Test
        void endEqualToStart_isZero() {
            LocalDate d = LocalDate.of(2026, 6, 15);
            assertThat(calc.businessDaysBetween(d, d, BusinessDayType.PRECISE, cfg)).isZero();
        }

        @Test
        void endBeforeStart_isZero() {
            assertThat(
                            calc.businessDaysBetween(
                                    LocalDate.of(2026, 6, 18),
                                    LocalDate.of(2026, 6, 15),
                                    BusinessDayType.PRECISE,
                                    cfg))
                    .isZero();
        }

        @Test
        void addAndBetween_areInverse() {
            LocalDate from = LocalDate.of(2026, 6, 15);
            for (BusinessDayType type : BusinessDayType.values()) {
                for (int n = 0; n <= 12; n++) {
                    LocalDate end = calc.addBusinessDays(from, n, type, cfg);
                    assertThat(calc.businessDaysBetween(from, end, type, cfg))
                            .as("between(from, addBusinessDays(from, %d, %s))", n, type)
                            .isEqualTo(n);
                }
            }
        }
    }
}
