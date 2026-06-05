package com.msfg.los.parties.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import com.msfg.los.platform.reference.UsStateCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "borrower_address")
@Getter
@Setter
public class BorrowerAddress extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID borrowerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AddressType addressType;

    @Column(nullable = false)
    private int ordinal;

    private String addressLine1;
    private String addressLine2;
    private String city;

    @Enumerated(EnumType.STRING)
    private UsStateCode state;

    private String postalCode;
    private String country = "US";

    @Enumerated(EnumType.STRING)
    private OwnershipType ownershipType;

    private Integer residencyDurationYears;
    private Integer residencyDurationMonths;
    private BigDecimal rentAmount;
    private Boolean rentVerified;
}
