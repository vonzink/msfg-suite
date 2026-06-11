package com.msfg.los.fees.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.msfg.los.fees.domain.InvoiceEntry;
import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceEntryResponse(
        UUID id,
        String feeLabel,
        BigDecimal amountDisclosed,
        BigDecimal invoiceAmount,
        BigDecimal borrowerPoc,
        @JsonProperty("final") boolean finalized,
        String comment) {

    public static InvoiceEntryResponse from(InvoiceEntry e) {
        return new InvoiceEntryResponse(
                e.getId(),
                e.getFeeLabel(),
                e.getAmountDisclosed(),
                e.getInvoiceAmount(),
                e.getBorrowerPoc(),
                e.isFinalized(),
                e.getComment());
    }
}
