package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.CreditOrderAction;
import com.msfg.los.aus.domain.CreditRequestType;

import java.util.List;
import java.util.UUID;

/** One credit order/reissue request to a credit vendor. */
public record CreditVendorRequest(UUID loanId, String providerCode, CreditOrderAction action,
        CreditRequestType requestType, boolean equifax, boolean experian, boolean transUnion,
        List<CreditBorrower> borrowers, String creditReportIdentifier) {}
