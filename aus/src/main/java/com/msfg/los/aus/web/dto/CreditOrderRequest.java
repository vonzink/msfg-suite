package com.msfg.los.aus.web.dto;

import com.msfg.los.aus.domain.CreditOrderAction;
import com.msfg.los.aus.domain.CreditRequestType;

import java.util.List;
import java.util.UUID;

/** Order/reissue a credit report. Null bureau flags default to true (tri-merge). */
public record CreditOrderRequest(CreditOrderAction action, CreditRequestType requestType,
        Boolean equifax, Boolean experian, Boolean transUnion,
        List<UUID> borrowerIds, String creditReportIdentifier) {}
