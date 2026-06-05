package com.msfg.los.income.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import com.msfg.los.platform.reference.UsStateCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "employment")
@Getter
@Setter
public class Employment extends TenantScopedEntity {

    @Column(nullable = false) private UUID loanId;
    @Column(nullable = false) private UUID borrowerId;
    @Column(nullable = false) private int ordinal;

    private String employerName;
    private String employerPhone;
    private String employerAddressLine1;
    private String employerAddressLine2;
    private String employerCity;
    @Enumerated(EnumType.STRING) private UsStateCode employerState;
    private String employerPostalCode;
    private String positionTitle;

    @Enumerated(EnumType.STRING) private EmploymentStatusType employmentStatus;
    @Enumerated(EnumType.STRING) private EmploymentClassificationType classification;
    private Boolean selfEmployed;
    @Enumerated(EnumType.STRING) private OwnershipInterestType ownershipShare;
    private Boolean employedByPartyToTransaction;

    private LocalDate startDate;
    private LocalDate endDate;
    private Integer monthsInLineOfWork;
}
