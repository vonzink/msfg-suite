package com.msfg.los.income.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "income_item")
@Getter
@Setter
public class IncomeItem extends TenantScopedEntity {

    @Column(nullable = false) private UUID loanId;
    @Column(nullable = false) private UUID borrowerId;
    private UUID employmentId;                 // null = other-source income

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private IncomeType incomeType;

    private BigDecimal monthlyAmount;
    private String description;
    @Column(nullable = false) private int ordinal;
}
