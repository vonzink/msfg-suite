package com.msfg.los.financials.domain;
public enum LiabilityType {
    // credit liabilities (isExpense = false)
    REVOLVING(false), INSTALLMENT(false), LEASE(false), OPEN_30_DAY(false), MORTGAGE_LOAN(false),
    HELOC(false), TAXES(false),
    // other liabilities & expenses (isExpense = true)
    ALIMONY(true), CHILD_SUPPORT(true), SEPARATE_MAINTENANCE(true), JOB_RELATED_EXPENSES(true), OTHER(true);
    private final boolean expense;
    LiabilityType(boolean expense) { this.expense = expense; }
    public boolean isExpense() { return expense; }
}
