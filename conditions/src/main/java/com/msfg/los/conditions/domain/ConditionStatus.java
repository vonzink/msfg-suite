package com.msfg.los.conditions.domain;

/**
 * Lifecycle of an underwriting condition. Persisted as the enum NAME (varchar) — values match the
 * mortgage-app source exactly: {@code Outstanding} (default), {@code Cleared}, {@code Waived}.
 */
public enum ConditionStatus {
    Outstanding,
    Cleared,
    Waived
}
