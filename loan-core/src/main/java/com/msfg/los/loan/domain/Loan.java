package com.msfg.los.loan.domain;

import com.msfg.los.platform.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "loan")
@Getter
@Setter
public class Loan extends AuditableEntity {

    @Column(nullable = false, unique = true, updatable = false)
    private String loanNumber;

    @Column(nullable = false)
    private UUID loanOfficerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanStatus status = LoanStatus.STARTED;

    @Enumerated(EnumType.STRING)
    private LoanPurposeType loanPurpose;

    @Enumerated(EnumType.STRING)
    private MortgageType mortgageType;

    @Enumerated(EnumType.STRING)
    private LienPriorityType lienPriority;

    @Enumerated(EnumType.STRING)
    private AmortizationType amortizationType;

    private BigDecimal noteAmount;

    @Embedded
    private SubjectProperty subjectProperty = new SubjectProperty();
}
