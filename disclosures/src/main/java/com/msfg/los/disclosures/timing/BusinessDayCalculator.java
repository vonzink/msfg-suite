package com.msfg.los.disclosures.timing;

import com.msfg.los.disclosures.domain.BusinessDayType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Regulatory business-day arithmetic for TRID disclosure timing.
 *
 * <p><b>Source of the convention</b> — 12 CFR 1026.2(a)(6) and its Official Interpretation
 * (Supplement I), confirmed against the CFPB's published regulation text:
 * https://www.consumerfinance.gov/rules-policy/regulations/1026/2/
 *
 * <ul>
 *   <li><b>PRECISE</b> ("the more precise rule", applied to §1026.19(e)/(f) receipt-of-disclosure
 *       timing and to rescission): every calendar day except Sundays and the federal legal holidays
 *       enumerated in 5 U.S.C. 6103(a). <b>Saturdays count as business days.</b>
 *   <li><b>GENERAL</b>: a day the creditor is open to the public for substantially all of its
 *       business functions (configurable per creditor via {@link GeneralBusinessDayConfig}).
 *   <li><b>Observed-holiday shifting</b> (per the same Official Interpretation): for the
 *       fixed-date holidays — New Year's Day (Jan 1), Juneteenth (Jun 19), Independence Day (Jul 4),
 *       Veterans Day (Nov 11), Christmas (Dec 25) — when the date falls on a Saturday the holiday is
 *       observed the prior Friday; when it falls on a Sunday it is observed the following Monday. The
 *       Monday-anchored holidays (MLK, Washington, Memorial, Labor, Columbus) and Thanksgiving never
 *       shift.
 *   <li><b>Counting</b>: "N business days after" a trigger begins the day AFTER the trigger; the
 *       trigger day itself is day 0. {@link #addBusinessDays} therefore returns the N-th business day
 *       strictly after {@code from}.
 * </ul>
 *
 * <p>This component is pure (no I/O, no persistence) and deterministic.
 */
@Component
public class BusinessDayCalculator {

    /**
     * True iff {@code d} is an <em>observed</em> federal holiday under 5 U.S.C. 6103(a), accounting
     * for Saturday→prior-Friday and Sunday→following-Monday shifting of the fixed-date holidays. The
     * actual calendar date of a holiday that has shifted is NOT itself a holiday (e.g. Sat Jul 4
     * 2026 returns false; the observed date Fri Jul 3 returns true).
     */
    public boolean isFederalHoliday(LocalDate d) {
        Objects.requireNonNull(d, "d");
        int year = d.getYear();

        // Fixed-date holidays, with observed shifting.
        if (d.equals(observed(LocalDate.of(year, Month.JANUARY, 1)))) return true; // New Year's Day
        if (d.equals(observed(LocalDate.of(year, Month.JUNE, 19)))) return true; // Juneteenth
        if (d.equals(observed(LocalDate.of(year, Month.JULY, 4)))) return true; // Independence Day
        if (d.equals(observed(LocalDate.of(year, Month.NOVEMBER, 11)))) return true; // Veterans Day
        if (d.equals(observed(LocalDate.of(year, Month.DECEMBER, 25)))) return true; // Christmas

        // A shifted holiday can land in an adjacent year (Jan 1 on Sat -> observed Dec 31 prior
        // year; Dec 25/31 on Sun -> observed following year). Cover both neighbours.
        if (d.equals(observed(LocalDate.of(year + 1, Month.JANUARY, 1)))) return true;
        if (d.equals(observed(LocalDate.of(year - 1, Month.DECEMBER, 25)))) return true;

        // Floating Monday-anchored holidays + Thanksgiving (these never shift).
        if (d.equals(nthWeekdayOfMonth(year, Month.JANUARY, DayOfWeek.MONDAY, 3))) return true; // MLK
        if (d.equals(nthWeekdayOfMonth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3)))
            return true; // Washington's Birthday
        if (d.equals(lastWeekdayOfMonth(year, Month.MAY, DayOfWeek.MONDAY)))
            return true; // Memorial Day
        if (d.equals(nthWeekdayOfMonth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1)))
            return true; // Labor Day
        if (d.equals(nthWeekdayOfMonth(year, Month.OCTOBER, DayOfWeek.MONDAY, 2)))
            return true; // Columbus Day
        if (d.equals(nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)))
            return true; // Thanksgiving

        return false;
    }

    /**
     * True iff {@code d} is a business day under the given {@code type}.
     *
     * <ul>
     *   <li>PRECISE: not a Sunday and not an observed federal holiday (Saturdays count).
     *   <li>GENERAL: {@code d}'s weekday is in {@code cfg.openDays()} and {@code d} is not an
     *       observed federal holiday.
     * </ul>
     */
    public boolean isBusinessDay(LocalDate d, BusinessDayType type, GeneralBusinessDayConfig cfg) {
        Objects.requireNonNull(d, "d");
        Objects.requireNonNull(type, "type");
        if (isFederalHoliday(d)) {
            return false;
        }
        return switch (type) {
            case PRECISE -> d.getDayOfWeek() != DayOfWeek.SUNDAY;
            case GENERAL -> {
                Objects.requireNonNull(cfg, "cfg");
                yield cfg.openDays().contains(d.getDayOfWeek());
            }
        };
    }

    /**
     * The N-th business day strictly after {@code from}. {@code n == 0} returns {@code from}
     * unchanged. {@code n} must be non-negative.
     */
    public LocalDate addBusinessDays(
            LocalDate from, int n, BusinessDayType type, GeneralBusinessDayConfig cfg) {
        Objects.requireNonNull(from, "from");
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0, was " + n);
        }
        LocalDate cursor = from;
        int counted = 0;
        while (counted < n) {
            cursor = cursor.plusDays(1);
            if (isBusinessDay(cursor, type, cfg)) {
                counted++;
            }
        }
        return cursor;
    }

    /**
     * Count of business days in the half-open interval {@code (a, b]} — i.e. excluding {@code a},
     * including {@code b}. Returns 0 when {@code b} is on or before {@code a}.
     */
    public int businessDaysBetween(
            LocalDate a, LocalDate b, BusinessDayType type, GeneralBusinessDayConfig cfg) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (!b.isAfter(a)) {
            return 0;
        }
        int count = 0;
        for (LocalDate cursor = a.plusDays(1); !cursor.isAfter(b); cursor = cursor.plusDays(1)) {
            if (isBusinessDay(cursor, type, cfg)) {
                count++;
            }
        }
        return count;
    }

    /** Saturday→prior Friday, Sunday→following Monday; otherwise unchanged. */
    private static LocalDate observed(LocalDate actual) {
        return switch (actual.getDayOfWeek()) {
            case SATURDAY -> actual.minusDays(1);
            case SUNDAY -> actual.plusDays(1);
            default -> actual;
        };
    }

    private static LocalDate nthWeekdayOfMonth(int year, Month month, DayOfWeek dow, int n) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
    }

    private static LocalDate lastWeekdayOfMonth(int year, Month month, DayOfWeek dow) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(dow));
    }
}
