package com.msfg.los.reo.domain;

import com.msfg.los.loan.domain.OccupancyType;
import com.msfg.los.loan.domain.PropertyType;
import com.msfg.los.platform.domain.TenantScopedEntity;
import com.msfg.los.platform.reference.UsStateCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "reo")
@Getter
@Setter
public class RealEstateOwned extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    private UUID ownerBorrowerId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    private boolean isSubjectProperty;

    private String addressLine1;
    private String addressLine2;
    private String city;

    @Enumerated(EnumType.STRING)
    private UsStateCode state;

    private String postalCode;

    @Enumerated(EnumType.STRING)
    private PropertyType propertyType;

    @Enumerated(EnumType.STRING)
    private OccupancyType intendedOccupancy;

    @Enumerated(EnumType.STRING)
    private ReoPropertyStatus propertyStatus;

    private BigDecimal marketValue;
    private BigDecimal grossMonthlyRentalIncome;
    private BigDecimal monthlyTaxes;
    private BigDecimal monthlyInsurance;
    private BigDecimal monthlyHoaDues;
    private BigDecimal monthlyMaintenance;
    private BigDecimal mortgageUnpaidBalance;
    private BigDecimal mortgageMonthlyPayment;
}
