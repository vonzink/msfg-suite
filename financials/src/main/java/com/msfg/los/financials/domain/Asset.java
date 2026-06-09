package com.msfg.los.financials.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "asset")
@Getter
@Setter
public class Asset extends TenantScopedEntity {

    @Column(nullable = false) private UUID loanId;
    @Column(nullable = false) private UUID borrowerId;
    @Column(nullable = false) private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private AssetType assetType;

    private String financialInstitution;
    private String accountNumber;
    private BigDecimal cashOrMarketValue;
    private Boolean verified;
}
