package com.msfg.los.disclosures.timing;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Per-creditor "general" business-day configuration for TRID timing.
 *
 * <p>Under 12 CFR 1026.2(a)(6), the <em>general</em> definition of "business day" is a day on which
 * the creditor is open to the public for substantially all of its business functions. That set of
 * open days varies per creditor, so it is configurable. {@link #DEFAULT} is the common Monday-Friday
 * profile.
 *
 * <p>The "precise" definition (all calendar days except Sundays and federal holidays) does NOT use
 * this config — see {@link com.msfg.los.disclosures.domain.BusinessDayType#PRECISE}.
 */
public record GeneralBusinessDayConfig(Set<DayOfWeek> openDays) {

    /** Standard Monday-through-Friday creditor schedule. */
    public static final GeneralBusinessDayConfig DEFAULT =
            new GeneralBusinessDayConfig(
                    EnumSet.of(
                            DayOfWeek.MONDAY,
                            DayOfWeek.TUESDAY,
                            DayOfWeek.WEDNESDAY,
                            DayOfWeek.THURSDAY,
                            DayOfWeek.FRIDAY));

    public GeneralBusinessDayConfig {
        // Reject null or empty: a creditor with no open days is nonsensical and would make
        // BusinessDayCalculator.addBusinessDays(..., GENERAL, cfg) loop forever (no business
        // day could ever satisfy the open-day check, and there is no iteration cap).
        if (openDays == null || openDays.isEmpty()) {
            throw new IllegalArgumentException("openDays must not be empty");
        }
        // Defensive copy so the record's set cannot be mutated by the caller after construction.
        // EnumSet.copyOf rejects an empty collection, but we have already rejected empty above.
        openDays = EnumSet.copyOf(openDays);
    }
}
