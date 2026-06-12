package com.msfg.los.pricing.domain;

import java.time.LocalDate;

/** Effective lock state. Only LOCKED-state rows persist; NOT_LOCKED/EXPIRED are computed. */
public enum RateLockStatus {
    NOT_LOCKED, LOCKED, EXPIRED;

    /** Expiration day itself still counts as locked; locks expire end-of-day. */
    public static RateLockStatus effective(LocalDate expirationDate, LocalDate today) {
        return expirationDate.isBefore(today) ? EXPIRED : LOCKED;
    }
}
