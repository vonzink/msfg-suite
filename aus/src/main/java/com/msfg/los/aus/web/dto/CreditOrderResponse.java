package com.msfg.los.aus.web.dto;

import com.msfg.los.aus.domain.CreditOrderAction;
import com.msfg.los.aus.domain.CreditOrderStatus;
import com.msfg.los.aus.domain.CreditRequestType;
import com.msfg.los.aus.domain.CreditScoreEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One credit order and its outcome (identifier, scores, stored report document). */
public record CreditOrderResponse(UUID id, String providerCode, CreditOrderAction action,
        CreditRequestType requestType, boolean equifax, boolean experian, boolean transUnion,
        List<UUID> borrowerIds, CreditOrderStatus status, String creditReportIdentifier,
        List<CreditScoreEntry> scores, UUID reportDocumentId, String requestedBy, Instant requestedAt) {}
