package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.AusVendor;

import java.util.UUID;

/** Everything an AUS vendor adapter needs to submit one casefile. */
public record AusSubmission(AusVendor vendor, UUID loanId, String existingCaseId,
        ResolvedCredentials credentials, CreditWiring creditWiring, AusLoanFile loanFile) {}
