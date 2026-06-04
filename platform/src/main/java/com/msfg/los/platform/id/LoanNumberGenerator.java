package com.msfg.los.platform.id;
public interface LoanNumberGenerator {
    /** Format a monotonic sequence value into a human-facing loan number. */
    String format(long sequenceValue);
}
