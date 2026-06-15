package com.msfg.los.disclosures.web.dto;

import com.msfg.los.disclosures.domain.DeliveryMethod;
import com.msfg.los.disclosures.domain.DisclosureIssuance;
import com.msfg.los.disclosures.domain.DisclosureKind;
import com.msfg.los.disclosures.domain.DisclosureStatus;
import com.msfg.los.disclosures.domain.ReceivedBasis;
import com.msfg.los.disclosures.domain.ResetReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** API view of one issued disclosure (LE or CD). */
public record DisclosureResponse(
        UUID id,
        DisclosureKind kind,
        int version,
        DisclosureStatus status,
        BigDecimal apr,
        BigDecimal financeCharge,
        BigDecimal amountFinanced,
        BigDecimal totalOfPayments,
        BigDecimal tip,
        DeliveryMethod deliveryMethod,
        Instant deliveredAt,
        ReceivedBasis receivedBasis,
        LocalDate computedReceivedDate,
        LocalDate earliestConsummationDate,
        UUID documentId,
        boolean resetTriggered,
        List<ResetReason> resetReasons,
        String requestedBy,
        Instant requestedAt) {

    public static DisclosureResponse from(DisclosureIssuance i) {
        return new DisclosureResponse(
                i.getId(),
                i.getKind(),
                i.getDisclosureVersion(),
                i.getStatus(),
                i.getApr(),
                i.getFinanceCharge(),
                i.getAmountFinanced(),
                i.getTotalOfPayments(),
                i.getTip(),
                i.getDeliveryMethod(),
                i.getDeliveredAt(),
                i.getReceivedBasis(),
                i.getComputedReceivedDate(),
                i.getEarliestConsummationDate(),
                i.getDocumentId(),
                i.isResetTriggered(),
                i.getResetReasons(),
                i.getRequestedBy(),
                i.getRequestedAt());
    }
}
