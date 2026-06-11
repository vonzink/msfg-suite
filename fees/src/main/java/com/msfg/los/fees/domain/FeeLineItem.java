package com.msfg.los.fees.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "fee_line_item")
@Getter
@Setter
public class FeeLineItem extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    @Column(nullable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeeSection section;

    private String label;

    private BigDecimal amount;

    private BigDecimal sellerConcession;

    private BigDecimal percent;
}
