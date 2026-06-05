package com.msfg.los.income.domain;

public enum IncomeType {
    // Employment income (attaches to an Employment of the same borrower)
    BASE(true), OVERTIME(true), BONUS(true), COMMISSION(true),
    MILITARY_BASE_PAY(true), MILITARY_ENTITLEMENTS(true),
    SELF_EMPLOYMENT_INCOME(true, true),   // may be negative (a loss)
    OTHER_EMPLOYMENT(true),
    // Other-source income (no employer; employmentId must be null)
    ALIMONY(false), CHILD_SUPPORT(false), SOCIAL_SECURITY(false), PENSION(false),
    DISABILITY(false), DIVIDENDS_INTEREST(false), NOTES_RECEIVABLE(false), ROYALTIES(false),
    TRUST(false), UNEMPLOYMENT(false), VA_BENEFITS_NON_EDUCATIONAL(false), PUBLIC_ASSISTANCE(false),
    FOSTER_CARE(false), SEPARATE_MAINTENANCE(false), AUTOMOBILE_ALLOWANCE(false), BOARDER_INCOME(false),
    HOUSING_ALLOWANCE(false), CAPITAL_GAINS(false), OTHER(false);

    private final boolean employment;
    private final boolean allowsNegative;
    IncomeType(boolean employment) { this(employment, false); }
    IncomeType(boolean employment, boolean allowsNegative) {
        this.employment = employment; this.allowsNegative = allowsNegative;
    }
    public boolean isEmployment() { return employment; }
    public boolean allowsNegative() { return allowsNegative; }
}
