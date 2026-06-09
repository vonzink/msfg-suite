package com.msfg.los.financials.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "liability")
@Getter
@Setter
public class Liability extends TenantScopedEntity {

    @Column(nullable = false) private UUID loanId;
    @Column(nullable = false) private UUID borrowerId;
    @Column(nullable = false) private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private LiabilityType liabilityType;

    private String creditorName;
    private String accountNumber;
    private BigDecimal unpaidBalance;
    private BigDecimal monthlyPayment;

    @Column(nullable = false) private boolean includeInDti = true;

    @Enumerated(EnumType.STRING)
    private DtiExclusionReason exclusionReason;

    private Integer monthsRemaining;
}
