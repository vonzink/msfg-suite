package com.msfg.los.pricing.service;

import com.msfg.los.pricing.domain.LockAction;
import com.msfg.los.pricing.domain.RateLockStatus;

/**
 * Pure rate-lock transition table (audit C4).
 *
 * <p>Encodes which {@link LockAction} is permitted from a given <em>effective</em>
 * {@link RateLockStatus} (NOT_LOCKED when no lock row exists; LOCKED/EXPIRED computed from the
 * lock's expiration date elsewhere). On a disallowed transition it throws
 * {@link LockStateConflictException} with the same 409 message the inline guards produced
 * (behavior-preserving — the persistence, pricing-engine calls, and append-only lock_event audit
 * stay in {@code PricingService}).
 *
 * <p>The loan-status terminal guard ({@code assertNotTerminal}) is a separate concern (loan
 * status, not lock status) and is intentionally NOT part of this matrix.
 */
public final class LockTransitions {

    private LockTransitions() {}

    /**
     * Assert that {@code action} is allowed from the given effective lock {@code current} status,
     * throwing {@link LockStateConflictException} (HTTP 409, code LOCK_STATE_CONFLICT) otherwise.
     *
     * @param current the effective lock status (NOT_LOCKED if there is no lock row)
     * @param action  the requested lock mutation
     */
    public static void assertAllowed(RateLockStatus current, LockAction action) {
        switch (action) {
            case CONTROL_YOUR_PRICE -> {
                // Allowed from NOT_LOCKED (create) or LOCKED (re-price). EXPIRED must relock.
                if (current == RateLockStatus.EXPIRED) {
                    throw new LockStateConflictException("Lock is EXPIRED — use relock");
                }
            }
            case EXTEND      -> requireState(current, RateLockStatus.LOCKED, "extend");
            case RATE_CHANGE -> requireState(current, RateLockStatus.LOCKED, "rate-change");
            case RELOCK      -> requireState(current, RateLockStatus.EXPIRED, "relock");
        }
    }

    /**
     * True iff {@code action} is allowed from {@code current} (non-throwing form, for callers/tests
     * that want a boolean view of the same table).
     */
    public static boolean isAllowed(RateLockStatus current, LockAction action) {
        try {
            assertAllowed(current, action);
            return true;
        } catch (LockStateConflictException e) {
            return false;
        }
    }

    private static void requireState(RateLockStatus current, RateLockStatus required, String action) {
        if (current == required) {
            return;
        }
        // Absent lock vs present-but-wrong-state produce distinct messages (preserved verbatim).
        if (current == RateLockStatus.NOT_LOCKED) {
            throw new LockStateConflictException("Loan is NOT_LOCKED — cannot " + action);
        }
        throw new LockStateConflictException("Lock is " + current + " — cannot " + action);
    }
}
