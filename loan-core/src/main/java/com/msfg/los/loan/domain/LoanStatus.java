package com.msfg.los.loan.domain;

public enum LoanStatus {
    STARTED, APPLICATION_IN_PROGRESS, SUBMITTED, IN_UNDERWRITING,
    APPROVED_WITH_CONDITIONS, CLEAR_TO_CLOSE, CLOSING, FUNDED,
    WITHDRAWN, CANCELLED, DENIED, SUSPENDED;

    public boolean isTerminal() {
        return this == FUNDED || this == WITHDRAWN || this == CANCELLED || this == DENIED;
    }
}
