package com.msfg.los.financials.domain;
public enum AssetType {
    // accounts (isAccount = true)
    CHECKING(true), SAVINGS(true), MONEY_MARKET(true), CERTIFICATE_OF_DEPOSIT(true), MUTUAL_FUND(true),
    STOCKS(true), BONDS(true), RETIREMENT(true), TRUST_ACCOUNT(true), BRIDGE_LOAN_NOT_DEPOSITED(true),
    CASH_VALUE_OF_LIFE_INSURANCE(true), INDIVIDUAL_DEVELOPMENT_ACCOUNT(true),
    // other assets & credits (isAccount = false)
    EARNEST_MONEY(false), EMPLOYER_ASSISTANCE(false), GIFT(false), GIFT_OF_EQUITY(false), GRANT(false),
    PROCEEDS_FROM_SALE_OF_NON_REAL_ESTATE(false), PROCEEDS_FROM_SALE_OF_REAL_ESTATE(false),
    SECURED_BORROWED_FUNDS(false), UNSECURED_BORROWED_FUNDS(false), RENT_CREDIT(false), SWEAT_EQUITY(false),
    TRADE_EQUITY(false), OTHER(false);
    private final boolean account;
    AssetType(boolean account) { this.account = account; }
    public boolean isAccount() { return account; }
}
