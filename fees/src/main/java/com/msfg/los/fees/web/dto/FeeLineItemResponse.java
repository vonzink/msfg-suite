package com.msfg.los.fees.web.dto;

import com.msfg.los.fees.domain.FeeLineItem;
import com.msfg.los.fees.domain.FeeSection;

import java.math.BigDecimal;
import java.util.UUID;

public record FeeLineItemResponse(
        UUID id,
        UUID loanId,
        int ordinal,
        FeeSection section,
        String label,
        BigDecimal amount,
        BigDecimal sellerConcession,
        BigDecimal percent,
        String paidTo,
        Boolean consumerCanShop,
        Boolean onWrittenList) {

    public static FeeLineItemResponse from(FeeLineItem f) {
        return new FeeLineItemResponse(
                f.getId(),
                f.getLoanId(),
                f.getOrdinal(),
                f.getSection(),
                f.getLabel(),
                f.getAmount(),
                f.getSellerConcession(),
                f.getPercent(),
                f.getPaidTo(),
                f.getConsumerCanShop(),
                f.getOnWrittenList());
    }
}
