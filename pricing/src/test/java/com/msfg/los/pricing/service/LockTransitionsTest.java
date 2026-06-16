package com.msfg.los.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.msfg.los.pricing.domain.LockAction;
import com.msfg.los.pricing.domain.RateLockStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pure-logic unit tests for the rate-lock transition table (audit C4), covering the FULL
 * (effective status × action) matrix — 3 statuses × 4 actions = 12 cells — including the illegal
 * transitions that must throw {@link LockStateConflictException} and the EXPIRED branch.
 *
 * <p>Allowed cells (from the inline guards this replaced):
 * <pre>
 *              NOT_LOCKED   LOCKED   EXPIRED
 *   CYP          allow      allow    conflict (use relock)
 *   EXTEND       conflict   allow    conflict
 *   RATE_CHANGE  conflict   allow    conflict
 *   RELOCK       conflict   conflict allow
 * </pre>
 */
class LockTransitionsTest {

    // status, action, allowed?
    @ParameterizedTest
    @CsvSource({
            // CONTROL_YOUR_PRICE
            "NOT_LOCKED, CONTROL_YOUR_PRICE, true",
            "LOCKED,     CONTROL_YOUR_PRICE, true",
            "EXPIRED,    CONTROL_YOUR_PRICE, false",
            // EXTEND
            "NOT_LOCKED, EXTEND,             false",
            "LOCKED,     EXTEND,             true",
            "EXPIRED,    EXTEND,             false",
            // RATE_CHANGE
            "NOT_LOCKED, RATE_CHANGE,        false",
            "LOCKED,     RATE_CHANGE,        true",
            "EXPIRED,    RATE_CHANGE,        false",
            // RELOCK
            "NOT_LOCKED, RELOCK,             false",
            "LOCKED,     RELOCK,             false",
            "EXPIRED,    RELOCK,             true",
    })
    void fullMatrix(RateLockStatus status, LockAction action, boolean allowed) {
        assertThat(LockTransitions.isAllowed(status, action)).isEqualTo(allowed);
        if (allowed) {
            assertThatCode(() -> LockTransitions.assertAllowed(status, action))
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> LockTransitions.assertAllowed(status, action))
                    .isInstanceOf(LockStateConflictException.class);
        }
    }

    // ── Message preservation (these strings surfaced as the 409 body before the refactor) ──

    @Test
    void cypFromExpired_usesRelockMessage() {
        assertThatThrownBy(() ->
                LockTransitions.assertAllowed(RateLockStatus.EXPIRED, LockAction.CONTROL_YOUR_PRICE))
                .isInstanceOf(LockStateConflictException.class)
                .hasMessage("Lock is EXPIRED — use relock");
    }

    @Test
    void extendWithoutLock_saysLoanNotLocked() {
        assertThatThrownBy(() ->
                LockTransitions.assertAllowed(RateLockStatus.NOT_LOCKED, LockAction.EXTEND))
                .hasMessage("Loan is NOT_LOCKED — cannot extend");
    }

    @Test
    void extendOnExpired_saysLockExpired() {
        assertThatThrownBy(() ->
                LockTransitions.assertAllowed(RateLockStatus.EXPIRED, LockAction.EXTEND))
                .hasMessage("Lock is EXPIRED — cannot extend");
    }

    @Test
    void rateChangeWithoutLock_saysLoanNotLocked() {
        assertThatThrownBy(() ->
                LockTransitions.assertAllowed(RateLockStatus.NOT_LOCKED, LockAction.RATE_CHANGE))
                .hasMessage("Loan is NOT_LOCKED — cannot rate-change");
    }

    @Test
    void relockWithoutLock_saysLoanNotLocked() {
        assertThatThrownBy(() ->
                LockTransitions.assertAllowed(RateLockStatus.NOT_LOCKED, LockAction.RELOCK))
                .hasMessage("Loan is NOT_LOCKED — cannot relock");
    }

    @Test
    void relockOnActiveLock_saysLockLocked() {
        assertThatThrownBy(() ->
                LockTransitions.assertAllowed(RateLockStatus.LOCKED, LockAction.RELOCK))
                .hasMessage("Lock is LOCKED — cannot relock");
    }

    @Test
    void conflictIsAlways409WithLockStateConflictCode() {
        try {
            LockTransitions.assertAllowed(RateLockStatus.NOT_LOCKED, LockAction.EXTEND);
        } catch (LockStateConflictException e) {
            assertThat(e.status().value()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("LOCK_STATE_CONFLICT");
        }
    }
}
