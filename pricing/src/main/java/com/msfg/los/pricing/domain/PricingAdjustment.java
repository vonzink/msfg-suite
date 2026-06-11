package com.msfg.los.pricing.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** One row of the loan's pricing-breakdown snapshot (ordinal-ordered). */
@Entity
@Table(name = "pricing_adjustment")
@Getter
@Setter
public class PricingAdjustment extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "row_type", nullable = false, length = 30)
    private PricingRowType rowType;

    @Column(name = "adjustment_percent", nullable = false)
    private BigDecimal adjustmentPercent;

    @Column(name = "dollar_amount", nullable = false)
    private BigDecimal dollarAmount;
}
