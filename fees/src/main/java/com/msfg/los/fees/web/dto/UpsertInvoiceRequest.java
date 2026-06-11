package com.msfg.los.fees.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record UpsertInvoiceRequest(
        @NotNull String feeLabel,
        BigDecimal amountDisclosed,
        BigDecimal invoiceAmount,
        BigDecimal borrowerPoc,
        @JsonProperty("final") boolean finalized,
        String comment) {}
