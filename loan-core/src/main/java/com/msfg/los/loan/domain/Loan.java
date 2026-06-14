package com.msfg.los.loan.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "loan")
@Getter
@Setter
public class Loan extends TenantScopedEntity {

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

    // §4 Loan Information fields
    @Enumerated(EnumType.STRING)
    private DocumentationType documentationType;

    private BigDecimal interestRate;
    private Integer loanTermMonths;
    private BigDecimal baseLoanAmount;
    private BigDecimal financedFeesAmount;
    private BigDecimal secondLoanAmount;
    private BigDecimal downPaymentAmount;
    private Integer qualifyingCreditScore;
    private BigDecimal proposedTaxesMonthly;
    private BigDecimal proposedHazardInsuranceMonthly;
    private BigDecimal proposedHoaDuesMonthly;
    private BigDecimal proposedMortgageInsuranceMonthly;

    // TRID consummation date (drives disclosure timing). Additive.
    @Column(name = "consummation_date")
    private LocalDate consummationDate;

    @Embedded
    private SubjectProperty subjectProperty = new SubjectProperty();
}
