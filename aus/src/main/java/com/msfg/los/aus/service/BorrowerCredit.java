package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.CreditOrderAction;

import java.util.UUID;

/** One borrower's credit action + report reference inside a {@link CreditWiring}. */
public record BorrowerCredit(UUID borrowerId, CreditOrderAction action, String creditReportIdentifier) {}
