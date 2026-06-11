package com.msfg.los.fees.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "invoice_entry")
@Getter
@Setter
public class InvoiceEntry extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    private String feeLabel;

    private BigDecimal amountDisclosed;

    private BigDecimal invoiceAmount;

    private BigDecimal borrowerPoc;

    @Column(nullable = false)
    private boolean finalized;

    private String comment;
}
