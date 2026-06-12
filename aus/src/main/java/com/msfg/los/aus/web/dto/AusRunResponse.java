package com.msfg.los.aus.web.dto;

import com.msfg.los.aus.domain.AusRecommendation;
import com.msfg.los.aus.domain.AusRunStatus;
import com.msfg.los.aus.domain.AusVendor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One AUS run — vendor identifiers, normalized + raw recommendation, findings document ids;
 * errorMessage is populated only on ERROR rows (failed vendor submissions).
 */
public record AusRunResponse(UUID id, AusVendor vendor, AusRunStatus status, String vendorCaseId,
        String vendorTransactionId, AusRecommendation recommendation, String rawRecommendation,
        String rawEligibility, String creditReportIdentifier, UUID findingsHtmlDocumentId,
        UUID findingsXmlDocumentId, List<String> messages, String requestedBy, Instant requestedAt,
        String errorMessage) {}
